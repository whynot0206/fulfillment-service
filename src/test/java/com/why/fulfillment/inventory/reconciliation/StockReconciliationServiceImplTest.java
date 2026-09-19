package com.why.fulfillment.inventory.reconciliation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.why.fulfillment.inventory.entity.SkuStock;
import com.why.fulfillment.inventory.mapper.SkuStockMapper;
import com.why.fulfillment.inventory.redis.RedisStockService;
import com.why.fulfillment.order.entity.AsyncOrderCommand;
import com.why.fulfillment.order.mapper.AsyncOrderCommandMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StockReconciliationServiceImplTest {

    private final AsyncOrderCommandMapper commandMapper = mock(AsyncOrderCommandMapper.class);
    private final SkuStockMapper stockMapper = mock(SkuStockMapper.class);
    private final RedisStockService redisStockService = mock(RedisStockService.class);
    private final StockReconciliationServiceImpl service = new StockReconciliationServiceImpl(
            commandMapper, stockMapper, redisStockService, new ObjectMapper());

    @Test
    void pendingCommandsAreSubtractedFromMysqlAvailableStock() {
        AsyncOrderCommand command = new AsyncOrderCommand();
        command.setCommandId(10L);
        command.setItemsJson("[{\"skuId\":1001,\"spuId\":1,\"count\":3}]");
        when(commandMapper.listOutstandingForReconciliation()).thenReturn(List.of(command));
        when(stockMapper.selectList(null)).thenReturn(List.of(stock(1001L, 20)));
        when(redisStockService.readStock(1001L)).thenReturn(OptionalLong.of(17));

        StockReconciliationReport report = service.inspect();

        assertTrue(report.consistent());
        assertEquals(1, report.checkedSkuCount());
        assertTrue(report.differences().isEmpty());
    }

    @Test
    void reportsDifferenceAndMalformedCommandWithoutChangingStock() {
        AsyncOrderCommand command = new AsyncOrderCommand();
        command.setCommandId(11L);
        command.setItemsJson("not-json");
        when(commandMapper.listOutstandingForReconciliation()).thenReturn(List.of(command));
        when(stockMapper.selectList(null)).thenReturn(List.of(stock(1001L, 20)));
        when(redisStockService.readStock(1001L)).thenReturn(OptionalLong.of(18));

        StockReconciliationReport report = service.inspect();

        assertFalse(report.consistent());
        assertEquals(1, report.differences().size());
        assertEquals(StockReconciliationItem.Status.DIFFERENT, report.differences().get(0).status());
        assertEquals(1, report.errors().size());
    }

    private SkuStock stock(long skuId, int available) {
        SkuStock stock = new SkuStock();
        stock.setSkuId(skuId);
        stock.setStock(available);
        stock.setLockStock(0);
        return stock;
    }
}
