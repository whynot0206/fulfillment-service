package com.why.fulfillment.inventory;

import com.why.fulfillment.inventory.entity.SkuStockLock;
import com.why.fulfillment.inventory.mapper.SkuStockLockMapper;
import com.why.fulfillment.inventory.mapper.SkuStockMapper;
import com.why.fulfillment.inventory.service.StockReservationItem;
import com.why.fulfillment.inventory.service.impl.InventoryReservationServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryReservationServiceImplTest {

    @Test
    void reservesSkusInStableOrderAndCreatesLockedRecords() {
        SkuStockMapper stockMapper = mock(SkuStockMapper.class);
        SkuStockLockMapper lockMapper = mock(SkuStockLockMapper.class);
        when(stockMapper.reduceStockAtomic(10L, 1)).thenReturn(1);
        when(stockMapper.reduceStockAtomic(20L, 1)).thenReturn(1);

        InventoryReservationServiceImpl service =
                new InventoryReservationServiceImpl(stockMapper, lockMapper);
        service.reserve(99L, List.of(
                new StockReservationItem(20L, 2L, 1),
                new StockReservationItem(10L, 1L, 1)));

        var calls = inOrder(stockMapper, lockMapper);
        calls.verify(stockMapper).reduceStockAtomic(10L, 1);
        calls.verify(lockMapper).insert(any(SkuStockLock.class));
        calls.verify(stockMapper).reduceStockAtomic(20L, 1);
        calls.verify(lockMapper).insert(any(SkuStockLock.class));
    }

    @Test
    void releasingAnAlreadyReleasedLockDoesNotTouchStockAgain() {
        SkuStockMapper stockMapper = mock(SkuStockMapper.class);
        SkuStockLockMapper lockMapper = mock(SkuStockLockMapper.class);
        SkuStockLock lock = new SkuStockLock();
        lock.setId(7L);
        lock.setSkuId(10L);
        lock.setCount(2);
        when(lockMapper.selectByOrderId(99L)).thenReturn(List.of(lock));
        when(lockMapper.markReleasedIfLocked(7L)).thenReturn(0);

        new InventoryReservationServiceImpl(stockMapper, lockMapper).release(99L);

        verify(stockMapper, org.mockito.Mockito.never()).addAvailableStock(10L, 2);
    }
}
