package com.why.fulfillment.inventory.metrics;

import com.why.fulfillment.inventory.redis.InventoryRedisReservationRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes inventory recovery signals from periodically refreshed values.
 */
@Component
public class InventoryServiceMetrics {

    private static final Logger log = LoggerFactory.getLogger(InventoryServiceMetrics.class);

    private final InventoryRedisReservationRepository reservationRepository;
    private final AtomicLong pendingReservations = new AtomicLong();
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong();
    private final AtomicLong reconciliationDifferences = new AtomicLong();
    private final AtomicLong reconciliationErrors = new AtomicLong();

    public InventoryServiceMetrics(MeterRegistry meterRegistry,
                                   InventoryRedisReservationRepository reservationRepository) {
        this.reservationRepository = reservationRepository;
        Gauge.builder("fulfillment.inventory.redis.reservation.pending",
                        pendingReservations, AtomicLong::doubleValue)
                .description("Number of Redis inventory reservations pending materialization")
                .register(meterRegistry);
        Gauge.builder("fulfillment.inventory.redis.reservation.oldest.age.seconds",
                        oldestPendingAgeSeconds, AtomicLong::doubleValue)
                .description("Age in seconds of the oldest pending Redis inventory reservation")
                .register(meterRegistry);
        Gauge.builder("fulfillment.inventory.reconciliation.difference.count",
                        reconciliationDifferences, AtomicLong::doubleValue)
                .description("Number of inventory reconciliation differences in the last inspection")
                .register(meterRegistry);
        Gauge.builder("fulfillment.inventory.reconciliation.error.count",
                        reconciliationErrors, AtomicLong::doubleValue)
                .description("Number of inventory reconciliation errors in the last inspection")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${fulfillment.metrics.refresh-ms:5000}",
            initialDelayString = "${fulfillment.metrics.initial-delay-ms:1000}")
    public void refresh() {
        try {
            InventoryRedisReservationRepository.PendingMetrics metrics =
                    reservationRepository.pendingMetrics();
            pendingReservations.set(metrics.pendingCount());
            oldestPendingAgeSeconds.set(metrics.oldestAgeSeconds());
        } catch (RuntimeException exception) {
            log.warn("could not refresh inventory Redis reservation metrics", exception);
        }
    }

    public void recordReconciliation(int differenceCount) {
        recordReconciliation(differenceCount, 0);
    }

    public void recordReconciliation(int differenceCount, int errorCount) {
        if (differenceCount < 0) {
            throw new IllegalArgumentException("reconciliation difference count cannot be negative");
        }
        if (errorCount < 0) {
            throw new IllegalArgumentException("reconciliation error count cannot be negative");
        }
        reconciliationDifferences.set(differenceCount);
        reconciliationErrors.set(errorCount);
    }
}
