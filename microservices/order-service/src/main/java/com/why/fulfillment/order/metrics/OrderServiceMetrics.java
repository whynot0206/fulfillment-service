package com.why.fulfillment.order.metrics;

import com.why.fulfillment.order.domain.RedisOrderCommand;
import com.why.fulfillment.order.repository.OrderRepository;
import com.why.fulfillment.order.repository.RedisOrderCommandRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes durable order workflow state as gauges without querying MySQL on every scrape.
 */
@Component
public class OrderServiceMetrics {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceMetrics.class);

    private static final Map<Integer, String> REDIS_COMMAND_STATUSES = statuses(
            RedisOrderCommand.PREPARING, "preparing",
            RedisOrderCommand.READY, "ready",
            RedisOrderCommand.PROCESSING, "processing",
            RedisOrderCommand.SUCCEEDED, "succeeded",
            RedisOrderCommand.DEAD, "dead");

    private static final Map<Integer, String> OUTBOX_STATUSES = statuses(
            0, "pending",
            1, "processing",
            2, "sent",
            3, "dead");

    private final RedisOrderCommandRepository redisCommandRepository;
    private final OrderRepository orderRepository;
    private final Map<Integer, AtomicLong> redisCommandCounts;
    private final Map<Integer, AtomicLong> outboxCounts;

    public OrderServiceMetrics(MeterRegistry meterRegistry,
                               RedisOrderCommandRepository redisCommandRepository,
                               OrderRepository orderRepository) {
        this.redisCommandRepository = redisCommandRepository;
        this.orderRepository = orderRepository;
        this.redisCommandCounts = registerGauges(meterRegistry,
                "fulfillment.order.redis.command.count", REDIS_COMMAND_STATUSES,
                "Number of Redis order commands by durable workflow status");
        this.outboxCounts = registerGauges(meterRegistry,
                "fulfillment.order.outbox.event.count", OUTBOX_STATUSES,
                "Number of payment confirmation outbox events by durable workflow status");
    }

    @Scheduled(fixedDelayString = "${fulfillment.metrics.refresh-ms:5000}",
            initialDelayString = "${fulfillment.metrics.initial-delay-ms:1000}")
    public void refresh() {
        refreshRedisCommandCounts();
        refreshOutboxCounts();
    }

    private void refreshRedisCommandCounts() {
        try {
            applyCounts(redisCommandCounts, redisCommandRepository.countByStatus());
        } catch (RuntimeException exception) {
            log.warn("could not refresh Redis order command metrics", exception);
        }
    }

    private void refreshOutboxCounts() {
        try {
            applyCounts(outboxCounts, orderRepository.countConfirmationEventsByStatus());
        } catch (RuntimeException exception) {
            log.warn("could not refresh order outbox metrics", exception);
        }
    }

    private static void applyCounts(Map<Integer, AtomicLong> gauges, Map<Integer, Long> counts) {
        gauges.forEach((status, gauge) -> gauge.set(counts.getOrDefault(status, 0L)));
    }

    private static Map<Integer, String> statuses(Object... values) {
        Map<Integer, String> statuses = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            statuses.put((Integer) values[i], (String) values[i + 1]);
        }
        return Map.copyOf(statuses);
    }

    private static Map<Integer, AtomicLong> registerGauges(MeterRegistry meterRegistry,
                                                            String name,
                                                            Map<Integer, String> statuses,
                                                            String description) {
        Map<Integer, AtomicLong> gauges = new LinkedHashMap<>();
        statuses.forEach((status, label) -> {
            AtomicLong value = new AtomicLong();
            Gauge.builder(name, value, AtomicLong::doubleValue)
                    .description(description)
                    .tag("status", label)
                    .register(meterRegistry);
            gauges.put(status, value);
        });
        return gauges;
    }
}
