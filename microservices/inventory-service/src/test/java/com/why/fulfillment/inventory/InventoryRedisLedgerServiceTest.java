package com.why.fulfillment.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.why.fulfillment.api.inventory.InventoryReserveItem;
import com.why.fulfillment.inventory.redis.InventoryRedisLedgerService;
import com.why.fulfillment.inventory.redis.InventoryRedisReservationRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryRedisLedgerServiceTest {

    private final InventoryRedisReservationRepository repository =
            mock(InventoryRedisReservationRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final InventoryRedisLedgerService service =
            new InventoryRedisLedgerService(repository, objectMapper);

    @Test
    void recordPendingNormalizesItemsBeforePersisting() throws Exception {
        List<InventoryReserveItem> requested = List.of(
                new InventoryReserveItem(200L, 20L, 1),
                new InventoryReserveItem(100L, 10L, 2),
                new InventoryReserveItem(100L, 10L, 3));
        String normalizedJson = objectMapper.writeValueAsString(List.of(
                new InventoryReserveItem(100L, 10L, 5),
                new InventoryReserveItem(200L, 20L, 1)));
        when(repository.find(42L)).thenReturn(Optional.of(new InventoryRedisReservationRepository.LedgerEntry(
                42L, normalizedJson, InventoryRedisLedgerService.PENDING)));

        service.recordPending(42L, requested);

        verify(repository).insertIfAbsent(42L, normalizedJson, InventoryRedisLedgerService.PENDING);
        verify(repository).find(42L);
        verify(repository, never()).markMaterialized(eq(42L), eq(normalizedJson));
    }

    @Test
    void recordPendingRejectsDifferentPayloadForExistingOrder() throws Exception {
        String requestedJson = objectMapper.writeValueAsString(List.of(
                new InventoryReserveItem(100L, 10L, 2)));
        when(repository.find(42L)).thenReturn(Optional.of(new InventoryRedisReservationRepository.LedgerEntry(
                42L, "[{\"skuId\":100,\"spuId\":10,\"count\":3}]",
                InventoryRedisLedgerService.PENDING)));

        assertThrows(InventoryRedisLedgerService.LedgerConflictException.class,
                () -> service.recordPending(42L,
                        List.of(new InventoryReserveItem(100L, 10L, 2))));

        verify(repository).insertIfAbsent(42L, requestedJson, InventoryRedisLedgerService.PENDING);
    }

    @Test
    void recordMaterializedTransitionsPendingLedgerEntry() throws Exception {
        List<InventoryReserveItem> items = List.of(new InventoryReserveItem(100L, 10L, 2));
        String itemsJson = objectMapper.writeValueAsString(items);
        when(repository.find(42L)).thenReturn(Optional.of(new InventoryRedisReservationRepository.LedgerEntry(
                42L, itemsJson, InventoryRedisLedgerService.PENDING)));
        when(repository.markMaterialized(42L, itemsJson)).thenReturn(true);

        service.recordMaterialized(42L, items);

        verify(repository).insertIfAbsent(42L, itemsJson, InventoryRedisLedgerService.MATERIALIZED);
        verify(repository).markMaterialized(42L, itemsJson);
    }

    @Test
    void pendingReservationsExposeMalformedPayloadAsUnreadable() {
        when(repository.findPending()).thenReturn(List.of(new InventoryRedisReservationRepository.LedgerEntry(
                42L, "not-json", InventoryRedisLedgerService.PENDING)));

        List<InventoryRedisLedgerService.PendingReservation> pending = service.pendingReservations();

        assertEquals(1, pending.size());
        assertEquals(42L, pending.get(0).orderId());
        assertNull(pending.get(0).items());
    }
}
