package com.why.fulfillment.observability;

import com.why.fulfillment.order.entity.AsyncOrderCommand;
import com.why.fulfillment.order.mapper.AsyncOrderCommandMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Refreshes low-cardinality database gauges outside Prometheus scrape threads. */
@Component
@ConditionalOnProperty(
        name = {"fulfillment.scheduling.enabled", "fulfillment.metrics.enabled"},
        havingValue = "true", matchIfMissing = true)
public class OperationsMetricsCollector {

    private final AsyncOrderCommandMapper commandMapper;
    private final FulfillmentMetrics metrics;

    public OperationsMetricsCollector(AsyncOrderCommandMapper commandMapper, FulfillmentMetrics metrics) {
        this.commandMapper = commandMapper;
        this.metrics = metrics;
    }

    @Scheduled(initialDelayString = "${fulfillment.metrics.initial-delay-ms:1000}",
            fixedDelayString = "${fulfillment.metrics.fixed-delay-ms:5000}")
    public void refresh() {
        metrics.updateAsyncCurrent(
                commandMapper.countByStatus(AsyncOrderCommand.PENDING),
                commandMapper.countByStatus(AsyncOrderCommand.PROCESSING),
                commandMapper.countByStatus(AsyncOrderCommand.DEAD));
    }
}
