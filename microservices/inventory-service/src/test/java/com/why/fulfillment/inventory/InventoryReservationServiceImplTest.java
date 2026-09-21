package com.why.fulfillment.inventory;

import com.why.fulfillment.api.inventory.InventoryReserveItem;
import com.why.fulfillment.inventory.entity.SkuStockLock;
import com.why.fulfillment.inventory.mapper.SkuStockLockMapper;
import com.why.fulfillment.inventory.mapper.SkuStockMapper;
import com.why.fulfillment.inventory.mapper.InventoryReservationFenceMapper;
import com.why.fulfillment.inventory.service.InventoryIdempotencyConflictException;
import com.why.fulfillment.inventory.service.InventoryReservationRejectedException;
import com.why.fulfillment.inventory.service.impl.InventoryReservationServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryReservationServiceImplTest {

    @Test
    void repeatedReserveWithSamePayloadDoesNotDeductAgain() {
        SkuStockMapper stockMapper = mock(SkuStockMapper.class);
        SkuStockLockMapper lockMapper = mock(SkuStockLockMapper.class);
        InventoryReservationFenceMapper fenceMapper = activeFence(22L);
        InventoryReservationServiceImpl service = new InventoryReservationServiceImpl(stockMapper, lockMapper, fenceMapper);

        SkuStockLock existing = lock(22L, 101L, 7L, 2, SkuStockLock.LOCKED);
        when(lockMapper.selectByOrderIdForUpdate(22L)).thenReturn(List.of(), List.of(existing));
        when(lockMapper.insert(any())).thenReturn(1);
        when(stockMapper.reserve(7L, 2)).thenReturn(1);

        List<InventoryReserveItem> request = List.of(new InventoryReserveItem(7L, 101L, 2));
        service.reserve(22L, request);
        service.reserve(22L, request);

        verify(stockMapper, times(1)).reserve(7L, 2);
        verify(lockMapper, times(1)).insert(any());
    }

    @Test
    void reserveMergesDuplicateSkusAndAcquiresRowsInSkuOrder() {
        SkuStockMapper stockMapper = mock(SkuStockMapper.class);
        SkuStockLockMapper lockMapper = mock(SkuStockLockMapper.class);
        InventoryReservationFenceMapper fenceMapper = activeFence(23L);
        InventoryReservationServiceImpl service = new InventoryReservationServiceImpl(stockMapper, lockMapper, fenceMapper);
        when(lockMapper.selectByOrderIdForUpdate(23L)).thenReturn(List.of());
        when(lockMapper.insert(any())).thenReturn(1);
        when(stockMapper.reserve(any(), any())).thenReturn(1);

        service.reserve(23L, List.of(
                new InventoryReserveItem(20L, 2L, 1),
                new InventoryReserveItem(10L, 1L, 2),
                new InventoryReserveItem(20L, 2L, 3)));

        var calls = inOrder(lockMapper, stockMapper);
        calls.verify(lockMapper).insert(org.mockito.ArgumentMatchers.argThat(lock ->
                lock.getSkuId().equals(10L) && lock.getCount().equals(2)));
        calls.verify(stockMapper).reserve(10L, 2);
        calls.verify(lockMapper).insert(org.mockito.ArgumentMatchers.argThat(lock ->
                lock.getSkuId().equals(20L) && lock.getCount().equals(4)));
        calls.verify(stockMapper).reserve(20L, 4);
    }

    @Test
    void reusedOrderIdWithDifferentPayloadIsRejectedWithoutMutation() {
        SkuStockMapper stockMapper = mock(SkuStockMapper.class);
        SkuStockLockMapper lockMapper = mock(SkuStockLockMapper.class);
        InventoryReservationFenceMapper fenceMapper = activeFence(24L);
        InventoryReservationServiceImpl service = new InventoryReservationServiceImpl(stockMapper, lockMapper, fenceMapper);
        when(lockMapper.selectByOrderIdForUpdate(24L)).thenReturn(
                List.of(lock(24L, 101L, 10L, 2, SkuStockLock.LOCKED)));

        assertThrows(InventoryIdempotencyConflictException.class,
                () -> service.reserve(24L, List.of(new InventoryReserveItem(10L, 101L, 3))));

        verify(lockMapper, never()).insert(any());
        verify(stockMapper, never()).reserve(any(), any());
    }

    @Test
    void releaseIsIdempotentAfterTheFirstSuccessfulRelease() {
        SkuStockMapper stockMapper = mock(SkuStockMapper.class);
        SkuStockLockMapper lockMapper = mock(SkuStockLockMapper.class);
        InventoryReservationFenceMapper fenceMapper = activeFence(25L);
        InventoryReservationServiceImpl service = new InventoryReservationServiceImpl(stockMapper, lockMapper, fenceMapper);

        when(lockMapper.selectByOrderIdForUpdate(25L)).thenReturn(
                List.of(lock(25L, 101L, 10L, 2, SkuStockLock.LOCKED)),
                List.of(lock(25L, 101L, 10L, 2, SkuStockLock.RELEASED)));
        when(lockMapper.markReleasedIfLocked(101L)).thenReturn(1);
        when(stockMapper.release(10L, 2)).thenReturn(1);

        service.release(25L);
        service.release(25L);

        verify(lockMapper, times(1)).markReleasedIfLocked(101L);
        verify(stockMapper, times(1)).release(10L, 2);
    }

    @Test
    void cancellationFenceRejectsReserveThatArrivesAfterRelease() {
        SkuStockMapper stockMapper = mock(SkuStockMapper.class);
        SkuStockLockMapper lockMapper = mock(SkuStockLockMapper.class);
        InventoryReservationFenceMapper fenceMapper = mock(InventoryReservationFenceMapper.class);
        when(fenceMapper.selectStatusForUpdate(26L))
                .thenReturn(InventoryReservationFenceMapper.ACTIVE,
                        InventoryReservationFenceMapper.CANCELED);
        when(lockMapper.selectByOrderIdForUpdate(26L)).thenReturn(List.of());
        InventoryReservationServiceImpl service =
                new InventoryReservationServiceImpl(stockMapper, lockMapper, fenceMapper);

        service.release(26L);

        assertThrows(InventoryReservationRejectedException.class,
                () -> service.reserve(26L, List.of(new InventoryReserveItem(10L, 101L, 1))));
        verify(fenceMapper).updateStatus(26L, InventoryReservationFenceMapper.CANCELED);
        verify(stockMapper, never()).reserve(any(), any());
    }

    private SkuStockLock lock(long orderId, long id, long skuId, int count, int status) {
        SkuStockLock lock = new SkuStockLock();
        lock.setId(id);
        lock.setOrderId(orderId);
        lock.setSkuId(skuId);
        lock.setSpuId(101L);
        lock.setCount(count);
        lock.setStatus(status);
        return lock;
    }

    private InventoryReservationFenceMapper activeFence(long orderId) {
        InventoryReservationFenceMapper fenceMapper = mock(InventoryReservationFenceMapper.class);
        when(fenceMapper.selectStatusForUpdate(orderId))
                .thenReturn(InventoryReservationFenceMapper.ACTIVE);
        return fenceMapper;
    }
}
