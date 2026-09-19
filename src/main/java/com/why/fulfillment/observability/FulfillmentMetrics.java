package com.why.fulfillment.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/** Business metrics shared by Prometheus and the local operations dashboard. */
@Component
public class FulfillmentMetrics {

    private final Counter mysqlOrders;
    private final Counter redisOrders;
    private final Counter globalRateLimitRejections;
    private final Counter endpointRateLimitRejections;
    private final Counter asyncSucceeded;
    private final Counter asyncRetried;
    private final Counter asyncDead;
    private final AtomicLong asyncPending = new AtomicLong();
    private final AtomicLong asyncProcessing = new AtomicLong();
    private final AtomicLong asyncDeadCurrent = new AtomicLong();
    private final AtomicLong reconciliationDifferences = new AtomicLong();

    public FulfillmentMetrics(MeterRegistry registry) {
        mysqlOrders = counter(registry, "fulfillment.orders.accepted", "path", "mysql");
        redisOrders = counter(registry, "fulfillment.orders.accepted", "path", "redis");
        globalRateLimitRejections = counter(registry, "fulfillment.rate_limit.rejections", "layer", "global");
        endpointRateLimitRejections = counter(registry, "fulfillment.rate_limit.rejections", "layer", "endpoint");
        asyncSucceeded = counter(registry, "fulfillment.async_commands.outcomes", "outcome", "succeeded");
        asyncRetried = counter(registry, "fulfillment.async_commands.outcomes", "outcome", "retry");
        asyncDead = counter(registry, "fulfillment.async_commands.outcomes", "outcome", "dead");
        Gauge.builder("fulfillment.async_commands.current", asyncPending, AtomicLong::get)
                .tag("status", "pending").register(registry);
        Gauge.builder("fulfillment.async_commands.current", asyncProcessing, AtomicLong::get)
                .tag("status", "processing").register(registry);
        Gauge.builder("fulfillment.async_commands.current", asyncDeadCurrent, AtomicLong::get)
                .tag("status", "dead").register(registry);
        Gauge.builder("fulfillment.inventory.reconciliation.differences", reconciliationDifferences,
                        AtomicLong::get)
                .register(registry);
    }

    public static FulfillmentMetrics noop() {
        return new FulfillmentMetrics(new SimpleMeterRegistry());
    }

    public void orderAccepted(String path) {
        ("redis".equals(path) ? redisOrders : mysqlOrders).increment();
    }

    public void rateLimited(String layer) {
        ("endpoint".equals(layer) ? endpointRateLimitRejections : globalRateLimitRejections).increment();
    }

    public void asyncOutcome(String outcome) {
        switch (outcome) {
            case "succeeded" -> asyncSucceeded.increment();
            case "retry" -> asyncRetried.increment();
            case "dead" -> asyncDead.increment();
            default -> throw new IllegalArgumentException("unknown async outcome: " + outcome);
        }
    }

    public void updateAsyncCurrent(long pending, long processing, long dead) {
        asyncPending.set(pending);
        asyncProcessing.set(processing);
        asyncDeadCurrent.set(dead);
    }

    public void updateReconciliationDifferences(long count) {
        reconciliationDifferences.set(count);
    }

    public Snapshot snapshot() {
        return new Snapshot(mysqlOrders.count(), redisOrders.count(),
                globalRateLimitRejections.count(), endpointRateLimitRejections.count(),
                asyncSucceeded.count(), asyncRetried.count(), asyncDead.count(),
                asyncPending.get(), asyncProcessing.get(), asyncDeadCurrent.get(),
                reconciliationDifferences.get());
    }

    private Counter counter(MeterRegistry registry, String name, String tag, String value) {
        return Counter.builder(name).tag(tag, value).register(registry);
    }

    public record Snapshot(double mysqlOrdersAccepted,
                           double redisOrdersAccepted,
                           double globalRateLimitRejections,
                           double endpointRateLimitRejections,
                           double asyncSucceeded,
                           double asyncRetried,
                           double asyncDead,
                           long asyncPendingCurrent,
                           long asyncProcessingCurrent,
                           long asyncDeadCurrent,
                           long reconciliationDifferences) {
    }
}
