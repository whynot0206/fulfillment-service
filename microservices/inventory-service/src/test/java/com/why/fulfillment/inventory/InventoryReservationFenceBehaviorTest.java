package com.why.fulfillment.inventory;

import com.why.fulfillment.api.inventory.InventoryReserveItem;
import com.why.fulfillment.inventory.entity.SkuStockLock;
import com.why.fulfillment.inventory.mapper.InventoryReservationFenceMapper;
import com.why.fulfillment.inventory.mapper.SkuStockLockMapper;
import com.why.fulfillment.inventory.mapper.SkuStockMapper;
import com.why.fulfillment.inventory.service.InventoryReservationRejectedException;
import com.why.fulfillment.inventory.service.impl.InventoryReservationServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InventoryReservationFenceBehaviorTest {
    private final SkuStockMapper stock = mock(SkuStockMapper.class);
    private final SkuStockLockMapper locks = mock(SkuStockLockMapper.class);
    private final InventoryReservationFenceMapper fence = mock(InventoryReservationFenceMapper.class);
    private final InventoryReservationServiceImpl service = new InventoryReservationServiceImpl(stock, locks, fence);

    @Test
    void repeatedConfirmSeesTheCommittedFenceAndDoesNotConsumeLockedStockAgain() {
        when(fence.selectStatusForUpdate(10L)).thenReturn(
                InventoryReservationFenceMapper.ACTIVE, InventoryReservationFenceMapper.CONFIRMED);
        when(locks.selectByOrderIdForUpdate(10L)).thenReturn(
                List.of(row(1L, 1001L, SkuStockLock.LOCKED)),
                List.of(row(1L, 1001L, SkuStockLock.CONFIRMED)));
        when(locks.markConfirmedIfLocked(1L)).thenReturn(1);
        when(stock.confirm(1001L, 1)).thenReturn(1);

        service.confirm(10L);
        service.confirm(10L);

        verify(fence, times(2)).ensureExists(10L);
        verify(fence, times(2)).selectStatusForUpdate(10L);
        verify(locks).markConfirmedIfLocked(1L);
        verify(stock).confirm(1001L, 1);
        verify(stock, never()).reserve(any(), any());
        verify(stock, never()).release(any(), any());
    }

    @Test
    void confirmedFenceRejectsBothLateReleaseAndLateReserveBeforeStockIsTouched() {
        when(fence.selectStatusForUpdate(10L)).thenReturn(InventoryReservationFenceMapper.CONFIRMED);

        assertThatThrownBy(() -> service.release(10L)).isInstanceOf(InventoryReservationRejectedException.class);
        assertThatThrownBy(() -> service.reserve(10L, List.of(new InventoryReserveItem(1001L, 1L, 1))))
                .isInstanceOf(InventoryReservationRejectedException.class);

        verifyNoInteractions(locks, stock);
        verify(fence, never()).updateStatus(any(), anyInt());
    }

    @Test
    void canceledFenceRejectsALateConfirmationBeforeStockIsTouched() {
        when(fence.selectStatusForUpdate(10L)).thenReturn(InventoryReservationFenceMapper.CANCELED);

        assertThatThrownBy(() -> service.confirm(10L)).isInstanceOf(InventoryReservationRejectedException.class);

        verifyNoInteractions(locks, stock);
        verify(fence, never()).updateStatus(any(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"release", "confirm"})
    void releaseAndConfirmRetainFenceThenOrderedRowsThenConditionalStockUpdates(String operation) {
        when(fence.selectStatusForUpdate(10L)).thenReturn(InventoryReservationFenceMapper.ACTIVE);
        when(locks.selectByOrderIdForUpdate(10L)).thenReturn(
                List.of(row(1L, 1001L, SkuStockLock.LOCKED), row(2L, 1002L, SkuStockLock.LOCKED)));
        when(locks.markReleasedIfLocked(any())).thenReturn(1);
        when(locks.markConfirmedIfLocked(any())).thenReturn(1);
        when(stock.release(any(), any())).thenReturn(1);
        when(stock.confirm(any(), any())).thenReturn(1);

        if (operation.equals("release")) service.release(10L);
        else service.confirm(10L);

        var sequence = inOrder(fence, locks, stock);
        sequence.verify(fence).ensureExists(10L);
        sequence.verify(fence).selectStatusForUpdate(10L);
        sequence.verify(locks).selectByOrderIdForUpdate(10L);
        if (operation.equals("release")) {
            sequence.verify(locks).markReleasedIfLocked(1L);
            sequence.verify(stock).release(1001L, 1);
            sequence.verify(locks).markReleasedIfLocked(2L);
            sequence.verify(stock).release(1002L, 1);
            sequence.verify(fence).updateStatus(10L, InventoryReservationFenceMapper.CANCELED);
        } else {
            sequence.verify(locks).markConfirmedIfLocked(1L);
            sequence.verify(stock).confirm(1001L, 1);
            sequence.verify(locks).markConfirmedIfLocked(2L);
            sequence.verify(stock).confirm(1002L, 1);
            sequence.verify(fence).updateStatus(10L, InventoryReservationFenceMapper.CONFIRMED);
        }
    }

    private static SkuStockLock row(long id, long skuId, int status) {
        SkuStockLock lock = new SkuStockLock();
        lock.setId(id);
        lock.setOrderId(10L);
        lock.setSkuId(skuId);
        lock.setSpuId(1L);
        lock.setCount(1);
        lock.setStatus(status);
        return lock;
    }
}
