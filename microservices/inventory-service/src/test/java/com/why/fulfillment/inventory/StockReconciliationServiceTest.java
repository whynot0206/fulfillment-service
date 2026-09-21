package com.why.fulfillment.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.why.fulfillment.inventory.entity.SkuStock;
import com.why.fulfillment.inventory.mapper.SkuStockMapper;
import com.why.fulfillment.inventory.reconciliation.StockReconciliationService;
import com.why.fulfillment.inventory.redis.RedisStockService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StockReconciliationServiceTest {

    @Test
    void subtractsOutstandingRedisCommandsAndReportsOnlyDifferences() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SkuStockMapper stocks = mock(SkuStockMapper.class);
        RedisStockService redis = mock(RedisStockService.class);
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("command_id")).thenReturn(7L);
        when(row.getString("items_json")).thenReturn("""
                [{"skuId":100,"spuId":10,"count":3,"price":1.00}]
                """);
        doAnswer(invocation -> {
            invocation.<RowCallbackHandler>getArgument(1).processRow(row);
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class));

        SkuStock consistent = stock(100L, 10, 0);
        SkuStock different = stock(200L, 5, 0);
        when(stocks.selectAll()).thenReturn(List.of(consistent, different));
        when(redis.readStock(100L)).thenReturn(OptionalLong.of(7));
        when(redis.readStock(200L)).thenReturn(OptionalLong.of(4));

        var report = new StockReconciliationService(jdbc, stocks, redis, new ObjectMapper()).inspect();

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
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SkuStockMapper stocks = mock(SkuStockMapper.class);
        RedisStockService redis = mock(RedisStockService.class);
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("command_id")).thenReturn(8L);
        when(row.getString("items_json")).thenReturn("not-json");
        doAnswer(invocation -> {
            invocation.<RowCallbackHandler>getArgument(1).processRow(row);
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class));
        when(stocks.selectAll()).thenReturn(List.of());

        var report = new StockReconciliationService(jdbc, stocks, redis, new ObjectMapper()).inspect();

        assertEquals(List.of("command 8 items cannot be read"), report.errors());
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
