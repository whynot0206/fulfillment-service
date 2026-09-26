package com.why.fulfillment.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.why.fulfillment.api.inventory.InventoryClient;
import com.why.fulfillment.api.inventory.InventoryRedisCompensateRequest;
import com.why.fulfillment.api.inventory.InventoryRedisCompensateResponse;
import com.why.fulfillment.api.inventory.InventoryRedisMaterializeRequest;
import com.why.fulfillment.api.inventory.InventoryRedisMaterializeResponse;
import com.why.fulfillment.api.inventory.InventoryRedisReserveRequest;
import com.why.fulfillment.api.inventory.InventoryRedisReserveResponse;
import com.why.fulfillment.api.inventory.InventoryReserveItem;
import com.why.fulfillment.order.domain.RedisOrderCommand;
import com.why.fulfillment.order.repository.RedisOrderCommandRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
public class RedisOrderApplicationService {

    private static final long DEFAULT_TIMEOUT_SECONDS = 1800;
    private static final long MAX_TIMEOUT_SECONDS = 7 * 24 * 60 * 60;
    private static final TypeReference<List<RedisOrderItemCommand>> ITEMS_TYPE = new TypeReference<>() { };

    private final RedisOrderCommandRepository repository;
    private final InventoryClient inventoryClient;
    private final OrderApplicationService orderService;
    private final ObjectMapper objectMapper;

    public RedisOrderApplicationService(RedisOrderCommandRepository repository,
                                        InventoryClient inventoryClient,
                                        OrderApplicationService orderService,
                                        ObjectMapper objectMapper) {
        this.repository = repository;
        this.inventoryClient = inventoryClient;
        this.orderService = orderService;
        this.objectMapper = objectMapper;
    }

    public RedisOrderResult accept(CreateRedisOrderCommand input) {
        CreateRedisOrderCommand command = normalizeAndValidate(input);
        String itemsJson = serialize(command.items());
        boolean inserted = repository.insertPreparing(command.orderId(), command.userId(),
                command.totalAmount(), command.timeoutSeconds(), itemsJson);
        RedisOrderCommand stored = repository.findByOrderId(command.orderId())
                .orElseThrow(() -> new IllegalStateException("async command was not persisted"));
        if (!samePayload(stored, command, itemsJson)) {
            return result(stored, "CONFLICT", "orderId already has a different Redis command", true);
        }
        if (!inserted && stored.status() != RedisOrderCommand.PREPARING) {
            return result(stored, state(stored.status()), stored.lastError(), true);
        }
        return prepare(stored, !inserted);
    }

    public RedisOrderResult prepare(RedisOrderCommand command, boolean replayed) {
        List<RedisOrderItemCommand> items = deserialize(command.itemsJson());
        try {
            InventoryRedisReserveResponse response = inventoryClient.reserveRedis(
                    new InventoryRedisReserveRequest(command.orderId(), inventoryItems(items)));
            String status = response == null ? "UNKNOWN" : response.status();
            if (isOneOf(status, "RESERVED", "ALREADY_RESERVED")) {
                repository.markReadyIfPreparing(command.commandId());
                RedisOrderCommand ready = repository.findByOrderId(command.orderId()).orElse(command);
                return result(ready, "ACCEPTED", "Redis stock reserved; durable persistence scheduled", replayed);
            }
            if (isOneOf(status, "REJECTED", "CANCELED", "CONFLICT")) {
                String error = response == null ? "Redis reservation rejected" : response.error();
                repository.markDead(command.commandId(), RedisOrderCommand.PREPARING, null, error);
                return result(command, status, error, replayed);
            }
            return pendingPreparation(command, "inventory returned an unknown Redis reservation result", replayed);
        } catch (RuntimeException exception) {
            return pendingPreparation(command, "Redis reservation call failed", replayed);
        }
    }

    public void processReady(RedisOrderCommand command, String leaseOwner) {
        List<RedisOrderItemCommand> items = deserialize(command.itemsJson());
        OrderApplicationService.CreateOrderResult result = orderService.createPending(
                new OrderApplicationService.CreateOrderCommand(command.orderId(), command.userId(),
                        command.totalAmount(), command.timeoutSeconds(), items.stream()
                        .map(item -> new OrderApplicationService.OrderItemCommand(
                                item.skuId(), item.spuId(), item.count(), item.price())).toList()));
        if ("RESERVED".equals(result.state())) {
            try {
                InventoryRedisMaterializeResponse materialized = inventoryClient.materializeRedis(
                        new InventoryRedisMaterializeRequest(command.orderId(), inventoryItems(items)));
                if (materialized == null || !"MATERIALIZED".equalsIgnoreCase(materialized.status())) {
                    String error = materialized == null ? "empty materialization response" : materialized.error();
                    throw new IllegalStateException("Redis reservation projection failed: " + error);
                }
                if (!repository.markSucceeded(command.commandId(), leaseOwner)) {
                    throw new IllegalStateException("async command lease was lost");
                }
            } catch (RuntimeException exception) {
                throw new RedisProjectionPendingException(
                        "order was persisted; Redis reservation projection is pending", exception);
            }
            return;
        }
        if (isOneOf(result.state(), "FAILED", "COMPENSATED", "CONFLICT")) {
            if (!compensate(command, items)) {
                throw new IllegalStateException("Redis compensation did not complete");
            }
            repository.markDead(command.commandId(), RedisOrderCommand.PROCESSING, leaseOwner,
                    "order persistence ended in " + result.state());
            return;
        }
        throw new IllegalStateException("order persistence is not terminal: " + result.state());
    }

    public boolean compensate(RedisOrderCommand command) {
        return compensate(command, deserialize(command.itemsJson()));
    }

    private boolean compensate(RedisOrderCommand command, List<RedisOrderItemCommand> items) {
        try {
            InventoryRedisCompensateResponse response = inventoryClient.compensateRedis(
                    new InventoryRedisCompensateRequest(command.orderId(), inventoryItems(items)));
            return response != null && isOneOf(response.status(), "COMPENSATED", "ALREADY_COMPENSATED");
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private RedisOrderResult pendingPreparation(RedisOrderCommand command, String error, boolean replayed) {
        int retry = command.retryCount() + 1;
        repository.retryPreparing(command.commandId(), retry,
                LocalDateTime.now().plusSeconds(backoffSeconds(retry)), error);
        return result(command, "PREPARING", error + "; background retry scheduled", replayed);
    }

    private RedisOrderResult result(RedisOrderCommand command, String state, String message, boolean replayed) {
        return new RedisOrderResult(command.orderId(), command.commandId(), state, message, replayed);
    }

    private CreateRedisOrderCommand normalizeAndValidate(CreateRedisOrderCommand command) {
        if (command == null || command.orderId() <= 0 || command.userId() <= 0
                || command.totalAmount() == null || command.totalAmount().signum() < 0
                || !fitsMoneyColumn(command.totalAmount())
                || command.items() == null || command.items().isEmpty()
                || (command.timeoutSeconds() != null && (command.timeoutSeconds() <= 0
                || command.timeoutSeconds() > MAX_TIMEOUT_SECONDS))) {
            throw new IllegalArgumentException("valid order, timeout and items are required");
        }
        command.items().forEach(item -> {
            if (item == null || item.skuId() == null || item.skuId() <= 0 || item.spuId() == null
                    || item.spuId() <= 0 || item.count() == null || item.count() <= 0
                    || item.price() == null || item.price().signum() < 0
                    || !fitsMoneyColumn(item.price())) {
                throw new IllegalArgumentException("each item requires positive ids/count and non-negative price");
            }
        });
        List<RedisOrderItemCommand> items = command.items().stream()
                .sorted(Comparator.comparing(RedisOrderItemCommand::skuId)).toList();
        if (items.stream().map(RedisOrderItemCommand::skuId).distinct().count() != items.size()) {
            throw new IllegalArgumentException("duplicate skuId is not allowed");
        }
        return new CreateRedisOrderCommand(command.orderId(), command.userId(), command.totalAmount(),
                command.timeoutSeconds() == null ? DEFAULT_TIMEOUT_SECONDS : command.timeoutSeconds(), items);
    }

    private boolean samePayload(RedisOrderCommand stored, CreateRedisOrderCommand command, String itemsJson) {
        return stored.userId().equals(command.userId())
                && stored.totalAmount().compareTo(command.totalAmount()) == 0
                && stored.timeoutSeconds().equals(command.timeoutSeconds())
                && Objects.equals(stored.itemsJson(), itemsJson);
    }

    private String serialize(List<RedisOrderItemCommand> items) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("items cannot be serialized", exception);
        }
    }

    private List<RedisOrderItemCommand> deserialize(String json) {
        try {
            return objectMapper.readValue(json, ITEMS_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("stored items cannot be read", exception);
        }
    }

    private static List<InventoryReserveItem> inventoryItems(List<RedisOrderItemCommand> items) {
        return items.stream().map(item -> new InventoryReserveItem(item.skuId(), item.spuId(), item.count())).toList();
    }

    private static boolean isOneOf(String actual, String... expected) {
        if (actual == null) return false;
        for (String value : expected) if (value.equalsIgnoreCase(actual)) return true;
        return false;
    }

    private static long backoffSeconds(int retry) {
        return Math.min(300, 1L << Math.min(8, Math.max(0, retry - 1)));
    }

    private static boolean fitsMoneyColumn(BigDecimal value) {
        return value.scale() <= 2 && value.precision() - value.scale() <= 10;
    }

    private static String state(int status) {
        return switch (status) {
            case RedisOrderCommand.PREPARING -> "PREPARING";
            case RedisOrderCommand.READY, RedisOrderCommand.PROCESSING -> "ACCEPTED";
            case RedisOrderCommand.SUCCEEDED -> "SUCCEEDED";
            case RedisOrderCommand.DEAD -> "DEAD";
            default -> "UNKNOWN";
        };
    }

    public record CreateRedisOrderCommand(long orderId, long userId, BigDecimal totalAmount,
                                          Long timeoutSeconds, List<RedisOrderItemCommand> items) { }

    public record RedisOrderItemCommand(Long skuId, Long spuId, Integer count, BigDecimal price) { }

    public record RedisOrderResult(@JsonSerialize(using = ToStringSerializer.class) long orderId,
                                   Long commandId, String state,
                                   String message, boolean replayed) { }

    public static class RedisProjectionPendingException extends RuntimeException {
        public RedisProjectionPendingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
