package com.why.fulfillment.order.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.why.fulfillment.inventory.service.RedisInventoryService;
import com.why.fulfillment.inventory.service.StockReservationItem;
import com.why.fulfillment.order.entity.AsyncOrderCommand;
import com.why.fulfillment.order.entity.Order;
import com.why.fulfillment.order.mapper.AsyncOrderCommandMapper;
import com.why.fulfillment.order.service.OrderService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

/** Executes one claimed command in one MySQL transaction. */
@Service
public class AsyncOrderCommandProcessor {

    private static final TypeReference<List<StockReservationItem>> ITEMS_TYPE =
            new TypeReference<>() { };

    private final OrderService orderService;
    private final AsyncOrderCommandMapper commandMapper;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<RedisInventoryService> redisInventoryServices;

    public AsyncOrderCommandProcessor(OrderService orderService,
                                      AsyncOrderCommandMapper commandMapper,
                                      ObjectMapper objectMapper,
                                      ObjectProvider<RedisInventoryService> redisInventoryServices) {
        this.orderService = orderService;
        this.commandMapper = commandMapper;
        this.objectMapper = objectMapper;
        this.redisInventoryServices = redisInventoryServices;
    }

    /**
     * The command claim is committed before this method is called. The order,
     * inventory reservation and command completion update therefore share this
     * transaction and roll back together on any failure.
     */
    @Transactional(rollbackFor = Exception.class)
    public void processClaimed(AsyncOrderCommand command, String leaseOwner) {
        if (command == null || command.getCommandId() == null
                || command.getOrderId() == null || leaseOwner == null || leaseOwner.isBlank()) {
            throw new IllegalArgumentException("invalid claimed async order command");
        }

        List<StockReservationItem> items = deserializeItems(command.getItemsJson());
        if (command.getTimeoutMs() == null || command.getTimeoutMs() <= 0) {
            throw new IllegalArgumentException("invalid async order timeout");
        }

        Order order = new Order();
        order.setOrderId(command.getOrderId());
        order.setUserId(command.getUserId());
        order.setTotalAmount(command.getTotalAmount());
        orderService.createPending(order, items, Duration.ofMillis(command.getTimeoutMs()));

        if (commandMapper.markSucceeded(command.getCommandId(), leaseOwner) != 1) {
            // A worker whose lease expired must not commit an order after a
            // different worker has taken ownership of this command.
            throw new IllegalStateException("async order command lease was lost " + command.getCommandId());
        }
    }

    /** Compensates the Redis reservation before the command is marked dead. */
    public void compensate(AsyncOrderCommand command) {
        if (command == null || command.getOrderId() == null) {
            throw new IllegalArgumentException("invalid async order command for compensation");
        }
        List<StockReservationItem> items = deserializeItems(command.getItemsJson());
        RedisInventoryService service = redisInventoryServices.getIfAvailable();
        if (service == null) {
            throw new IllegalStateException("RedisInventoryService bean is required for dead-letter compensation");
        }
        service.compensate(command.getOrderId(), items);
    }

    private List<StockReservationItem> deserializeItems(String itemsJson) {
        if (itemsJson == null || itemsJson.isBlank()) {
            throw new IllegalArgumentException("async order command has no items");
        }
        try {
            List<StockReservationItem> items = objectMapper.readValue(itemsJson, ITEMS_TYPE);
            if (items == null || items.isEmpty()) {
                throw new IllegalArgumentException("async order command has no items");
            }
            return items;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid async order command items", exception);
        }
    }
}
