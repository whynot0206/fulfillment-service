package com.why.fulfillment.inventory.metrics;

import com.why.fulfillment.inventory.redis.InventoryRedisReservationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InventoryServiceMetricsTest {

    @Test
    void refreshesPendingLedgerGaugesAndRecordsReconciliationDifferences() {
        InventoryRedisReservationRepository repository = mock(InventoryRedisReservationRepository.class);
        when(repository.pendingMetrics()).thenReturn(
                new InventoryRedisReservationRepository.PendingMetrics(3, 41));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InventoryServiceMetrics metrics = new InventoryServiceMetrics(registry, repository);

        metrics.refresh();
        metrics.recordReconciliation(2, 1);

        assertEquals(3.0, registry.get("fulfillment.inventory.redis.reservation.pending").gauge().value());
        assertEquals(41.0, registry.get("fulfillment.inventory.redis.reservation.oldest.age.seconds")
                .gauge().value());
        assertEquals(2.0, registry.get("fulfillment.inventory.reconciliation.difference.count")
                .gauge().value());
        assertEquals(1.0, registry.get("fulfillment.inventory.reconciliation.error.count")
                .gauge().value());
    }

    @Test
    void rejectsNegativeReconciliationCounts() {
        InventoryRedisReservationRepository repository = mock(InventoryRedisReservationRepository.class);
        InventoryServiceMetrics metrics = new InventoryServiceMetrics(
                new SimpleMeterRegistry(), repository);

        assertThrows(IllegalArgumentException.class, () -> metrics.recordReconciliation(-1));
    }
}
