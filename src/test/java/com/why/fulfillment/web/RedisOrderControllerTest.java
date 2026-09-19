package com.why.fulfillment.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.why.fulfillment.inventory.service.RedisInventoryService;
import com.why.fulfillment.inventory.service.StockReservationItem;
import com.why.fulfillment.order.entity.AsyncOrderCommand;
import com.why.fulfillment.order.entity.Order;
import com.why.fulfillment.order.service.AsyncOrderCommandService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisOrderControllerTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void successfulPreDeductionReturnsAcceptedCommand() throws Exception {
        RedisInventoryService redis = mock(RedisInventoryService.class);
        AsyncOrderCommandService commands = mock(AsyncOrderCommandService.class);
        RedisOrderController controller = new RedisOrderController(redis, commands);
        RedisOrderController.RedisOrderRequest request = request();
        when(commands.findByOrderId(7001L)).thenReturn(null);
        when(redis.preDeduct(any(), any())).thenReturn(true);
        AsyncOrderCommand command = new AsyncOrderCommand();
        command.setCommandId(91L);
        command.setOrderId(7001L);
        command.setStatus(AsyncOrderCommand.PENDING);
        when(commands.enqueue(any(Order.class), any(), any(Duration.class))).thenReturn(command);

        var response = controller.create(request);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(91L, response.getBody().data().commandId());
    }

    @Test
    void enqueueFailureCompensatesRedis() throws Exception {
        RedisInventoryService redis = mock(RedisInventoryService.class);
        AsyncOrderCommandService commands = mock(AsyncOrderCommandService.class);
        RedisOrderController controller = new RedisOrderController(redis, commands);
        RedisOrderController.RedisOrderRequest request = request();
        when(commands.findByOrderId(7001L)).thenReturn(null);
        when(redis.preDeduct(any(), any())).thenReturn(true);
        when(commands.enqueue(any(Order.class), any(), any(Duration.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(IllegalStateException.class, () -> controller.create(request));

        verify(redis).compensate(any(), any());
    }

    @Test
    void existingCommandSkipsRedisDeduction() throws Exception {
        RedisInventoryService redis = mock(RedisInventoryService.class);
        AsyncOrderCommandService commands = mock(AsyncOrderCommandService.class);
        RedisOrderController controller = new RedisOrderController(redis, commands);
        AsyncOrderCommand command = new AsyncOrderCommand();
        command.setCommandId(92L);
        command.setOrderId(7001L);
        command.setStatus(AsyncOrderCommand.SUCCEEDED);
        when(commands.findByOrderId(7001L)).thenReturn(command);
        when(commands.enqueue(any(Order.class), any(), any(Duration.class))).thenReturn(command);

        controller.create(request());

        verify(redis, never()).preDeduct(any(), any());
    }

    private RedisOrderController.RedisOrderRequest request() throws Exception {
        return json.readValue("""
                {"orderId":7001,"userId":8,"totalAmount":9.90,"timeoutSeconds":60,
                 "items":[{"skuId":1001,"spuId":2001,"count":1}]}
                """, RedisOrderController.RedisOrderRequest.class);
    }
}
