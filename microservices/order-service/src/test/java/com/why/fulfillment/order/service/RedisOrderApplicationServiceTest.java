package com.why.fulfillment.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.why.fulfillment.api.inventory.InventoryClient;
import com.why.fulfillment.api.inventory.InventoryRedisMaterializeResponse;
import com.why.fulfillment.order.domain.RedisOrderCommand;
import com.why.fulfillment.order.repository.RedisOrderCommandRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisOrderApplicationServiceTest {

    private final RedisOrderCommandRepository repository = mock(RedisOrderCommandRepository.class);
    private final InventoryClient inventory = mock(InventoryClient.class);
    private final OrderApplicationService orders = mock(OrderApplicationService.class);
    private final RedisOrderApplicationService service = new RedisOrderApplicationService(
            repository, inventory, orders, new ObjectMapper());

    @Test
    void persistsIntentBeforeCallingRedisAndKeepsUnknownResultRecoverable() {
        RedisOrderApplicationService.CreateRedisOrderCommand request = request(10L, 2);
        RedisOrderCommand stored = stored(1L, 10L, 20L, 2, RedisOrderCommand.PREPARING);
        when(repository.insertPreparing(anyLong(), anyLong(), any(), anyLong(), anyString())).thenReturn(true);
        when(repository.findByOrderId(10L)).thenReturn(Optional.of(stored));
        when(inventory.reserveRedis(any())).thenThrow(new IllegalStateException("timeout"));
        when(repository.retryPreparing(anyLong(), anyInt(), any(), anyString())).thenReturn(true);

        RedisOrderApplicationService.RedisOrderResult result = service.accept(request);

        assertEquals("PREPARING", result.state());
        var order = inOrder(repository, inventory);
        order.verify(repository).insertPreparing(anyLong(), anyLong(), any(), anyLong(), anyString());
        order.verify(inventory).reserveRedis(any());
        verify(repository).retryPreparing(anyLong(), anyInt(), any(), anyString());
    }

    @Test
    void differentPayloadForExistingOrderIsRejectedBeforeRedis() {
        RedisOrderCommand stored = stored(1L, 10L, 20L, 2, RedisOrderCommand.READY);
        when(repository.insertPreparing(anyLong(), anyLong(), any(), anyLong(), anyString())).thenReturn(false);
        when(repository.findByOrderId(10L)).thenReturn(Optional.of(stored));

        RedisOrderApplicationService.RedisOrderResult result = service.accept(request(10L, 3));

        assertEquals("CONFLICT", result.state());
        verify(inventory, never()).reserveRedis(any());
    }

    @Test
    void marksCommandSucceededOnlyAfterRedisReservationIsMaterialized() {
        RedisOrderCommand command = stored(1L, 10L, 20L, 2, RedisOrderCommand.PROCESSING);
        when(orders.createPending(any())).thenReturn(
                new OrderApplicationService.CreateOrderResult(10L, "RESERVED", "inventory reserved", false));
        when(inventory.materializeRedis(any())).thenReturn(InventoryRedisMaterializeResponse.materialized());
        when(repository.markSucceeded(1L, "worker-1")).thenReturn(true);

        service.processReady(command, "worker-1");

        var order = inOrder(orders, inventory, repository);
        order.verify(orders).createPending(any());
        order.verify(inventory).materializeRedis(any());
        order.verify(repository).markSucceeded(1L, "worker-1");
    }

    private static RedisOrderApplicationService.CreateRedisOrderCommand request(long orderId, int count) {
        return new RedisOrderApplicationService.CreateRedisOrderCommand(orderId, 20L,
                new BigDecimal("10.00"), 30L,
                List.of(new RedisOrderApplicationService.RedisOrderItemCommand(
                        100L, 200L, count, new BigDecimal("5.00"))));
    }

    private static RedisOrderCommand stored(long commandId, long orderId, long userId,
                                            int count, int status) {
        String json = "[{\"skuId\":100,\"spuId\":200,\"count\":" + count
                + ",\"price\":5.00}]";
        return new RedisOrderCommand(commandId, orderId, userId, new BigDecimal("10.00"),
                30L, json, status, status != RedisOrderCommand.PREPARING, 0,
                LocalDateTime.now(), null, null, null);
    }
}
