package com.why.fulfillment.order.metrics;

import com.why.fulfillment.order.domain.RedisOrderCommand;
import com.why.fulfillment.order.repository.OrderRepository;
import com.why.fulfillment.order.repository.RedisOrderCommandRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderServiceMetricsTest {

    @Test
    void refreshesRedisCommandAndOutboxStatusGauges() {
        RedisOrderCommandRepository commands = mock(RedisOrderCommandRepository.class);
        OrderRepository orders = mock(OrderRepository.class);
        when(commands.countByStatus()).thenReturn(Map.of(
                RedisOrderCommand.PREPARING, 2L,
                RedisOrderCommand.PROCESSING, 3L,
                RedisOrderCommand.SUCCEEDED, 4L,
                RedisOrderCommand.DEAD, 5L));
        when(orders.countConfirmationEventsByStatus()).thenReturn(Map.of(
                0, 7L, 1, 2L, 2, 1L, 3, 4L));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OrderServiceMetrics metrics = new OrderServiceMetrics(registry, commands, orders);
        metrics.refresh();

        assertEquals(2.0, gauge(registry, "fulfillment.order.redis.command.count", "preparing"));
        assertEquals(0.0, gauge(registry, "fulfillment.order.redis.command.count", "ready"));
        assertEquals(3.0, gauge(registry, "fulfillment.order.redis.command.count", "processing"));
        assertEquals(5.0, gauge(registry, "fulfillment.order.redis.command.count", "dead"));
        assertEquals(7.0, gauge(registry, "fulfillment.order.outbox.event.count", "pending"));
        assertEquals(1.0, gauge(registry, "fulfillment.order.outbox.event.count", "sent"));
    }

    private static double gauge(SimpleMeterRegistry registry, String name, String status) {
        return registry.get(name).tag("status", status).gauge().value();
    }
}
