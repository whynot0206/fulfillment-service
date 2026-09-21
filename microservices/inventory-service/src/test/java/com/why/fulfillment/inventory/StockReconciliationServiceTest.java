package com.why.fulfillment.inventory;

import com.why.fulfillment.api.inventory.InventoryReserveItem;
import com.why.fulfillment.inventory.entity.SkuStock;
import com.why.fulfillment.inventory.mapper.SkuStockMapper;
import com.why.fulfillment.inventory.reconciliation.StockReconciliationService;
import com.why.fulfillment.inventory.redis.InventoryRedisLedgerService;
import com.why.fulfillment.inventory.redis.RedisStockService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StockReconciliationServiceTest {

    @Test
    void subtractsOutstandingRedisCommandsAndReportsOnlyDifferences() throws Exception {
        SkuStockMapper stocks = mock(SkuStockMapper.class);
        RedisStockService redis = mock(RedisStockService.class);
        InventoryRedisLedgerService ledger = mock(InventoryRedisLedgerService.class);
        when(ledger.pendingReservations()).thenReturn(List.of(
                new InventoryRedisLedgerService.PendingReservation(7L,
                        List.of(new InventoryReserveItem(100L, 10L, 3)))));

        SkuStock consistent = stock(100L, 10, 0);
        SkuStock different = stock(200L, 5, 0);
        when(stocks.selectAll()).thenReturn(List.of(consistent, different));
        when(redis.readStock(100L)).thenReturn(OptionalLong.of(7));
        when(redis.readStock(200L)).thenReturn(OptionalLong.of(4));

        var report = new StockReconciliationService(stocks, redis, ledger).inspect();

        assertEquals(2, report.checkedSkuCount());
        assertTrue(report.errors().isEmpty());
        assertEquals(1, report.differences().size());
        var mismatch = report.differences().get(0);
        assertEquals(200L, mismatch.skuId());
        assertEquals(5, mismatch.expectedRedisStock());
        assertEquals(4L, mismatch.actualRedisStock());
        assertEquals("DIFFERENT", mismatch.status());
    }

    @Test
    void reportsMalformedCommandWithoutChangingInventory() throws Exception {
        SkuStockMapper stocks = mock(SkuStockMapper.class);
        RedisStockService redis = mock(RedisStockService.class);
        InventoryRedisLedgerService ledger = mock(InventoryRedisLedgerService.class);
        when(ledger.pendingReservations()).thenReturn(List.of(
                new InventoryRedisLedgerService.PendingReservation(8L, null)));
        when(stocks.selectAll()).thenReturn(List.of());

        var report = new StockReconciliationService(stocks, redis, ledger).inspect();

        assertEquals(List.of("inventory Redis ledger 8 items cannot be read"), report.errors());
        assertTrue(report.differences().isEmpty());
    }

    private static SkuStock stock(long skuId, int available, int locked) {
        SkuStock stock = new SkuStock();
        stock.setSkuId(skuId);
        stock.setStock(available);
        stock.setLockStock(locked);
        return stock;
    }
}
