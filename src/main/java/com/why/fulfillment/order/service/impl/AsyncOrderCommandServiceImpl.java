package com.why.fulfillment.order.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.why.fulfillment.inventory.service.StockReservationItem;
import com.why.fulfillment.order.entity.AsyncOrderCommand;
import com.why.fulfillment.order.entity.Order;
import com.why.fulfillment.order.mapper.AsyncOrderCommandMapper;
import com.why.fulfillment.order.service.AsyncOrderCommandService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

@Service
public class AsyncOrderCommandServiceImpl implements AsyncOrderCommandService {

    private final AsyncOrderCommandMapper commandMapper;
    private final ObjectMapper objectMapper;

    public AsyncOrderCommandServiceImpl(AsyncOrderCommandMapper commandMapper,
                                        ObjectMapper objectMapper) {
        this.commandMapper = commandMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AsyncOrderCommand enqueue(Order order,
                                     List<StockReservationItem> items,
                                     Duration timeout) {
        validate(order, items, timeout);
        String itemsJson = serializeItems(items);

        AsyncOrderCommand command = new AsyncOrderCommand();
        command.setOrderId(order.getOrderId());
        command.setUserId(order.getUserId());
        command.setTotalAmount(order.getTotalAmount());
        command.setTimeoutMs(timeout.toMillis());
        command.setItemsJson(itemsJson);

        // INSERT IGNORE makes retries from the Redis caller idempotent without
        // turning a duplicate-key exception into a rollback-only transaction.
        if (commandMapper.insertIfAbsent(command) == 1) {
            command.setStatus(AsyncOrderCommand.PENDING);
            command.setRetryCount(0);
            return command;
        }

        AsyncOrderCommand existing = commandMapper.selectByOrderId(order.getOrderId());
        if (existing == null) {
            throw new IllegalStateException("failed to enqueue async order command " + order.getOrderId());
        }
        if (!sameCommand(existing, command)) {
            throw new IllegalStateException("order already has a different async command " + order.getOrderId());
        }
        return existing;
    }

    @Override
    public AsyncOrderCommand findByOrderId(Long orderId) {
        if (orderId == null || orderId <= 0) {
            throw new IllegalArgumentException("orderId must be positive");
        }
        return commandMapper.selectByOrderId(orderId);
    }

    private void validate(Order order, List<StockReservationItem> items, Duration timeout) {
        if (order == null || order.getOrderId() == null || order.getOrderId() <= 0
                || order.getUserId() == null || order.getUserId() <= 0
                || order.getTotalAmount() == null || order.getTotalAmount().signum() < 0
                || items == null || items.isEmpty()
                || timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("invalid async order command");
        }
        for (StockReservationItem item : items) {
            if (item == null || item.skuId() == null || item.spuId() == null || item.count() <= 0) {
                throw new IllegalArgumentException("invalid stock reservation item");
            }
        }
    }

    private String serializeItems(List<StockReservationItem> items) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("failed to serialize stock reservation items", exception);
        }
    }

    private boolean sameCommand(AsyncOrderCommand existing, AsyncOrderCommand requested) {
        return Objects.equals(existing.getOrderId(), requested.getOrderId())
                && Objects.equals(existing.getUserId(), requested.getUserId())
                && sameAmount(existing.getTotalAmount(), requested.getTotalAmount())
                && Objects.equals(existing.getTimeoutMs(), requested.getTimeoutMs())
                && Objects.equals(existing.getItemsJson(), requested.getItemsJson());
    }

    private boolean sameAmount(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }
}
