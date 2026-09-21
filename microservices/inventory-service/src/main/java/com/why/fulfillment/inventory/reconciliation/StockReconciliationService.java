package com.why.fulfillment.inventory.reconciliation;

import com.why.fulfillment.inventory.entity.SkuStock;
import com.why.fulfillment.inventory.mapper.SkuStockMapper;
import com.why.fulfillment.inventory.redis.InventoryRedisLedgerService;
import com.why.fulfillment.inventory.redis.RedisStockService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

@Service
public class StockReconciliationService {

    private final SkuStockMapper stockMapper;
    private final RedisStockService redisInventoryService;
    private final InventoryRedisLedgerService ledgerService;

    public StockReconciliationService(SkuStockMapper stockMapper,
                                      RedisStockService redisInventoryService,
                                      InventoryRedisLedgerService ledgerService) {
        this.stockMapper = stockMapper;
        this.redisInventoryService = redisInventoryService;
        this.ledgerService = ledgerService;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReconciliationReport inspect() {
        Map<Long, Integer> pending = new HashMap<>();
        List<String> errors = new ArrayList<>();
        for (var reservation : ledgerService.pendingReservations()) {
            if (reservation.items() == null) {
                errors.add("inventory Redis ledger " + reservation.orderId() + " items cannot be read");
                continue;
            }
            try {
                reservation.items().forEach(item -> pending.merge(item.skuId(), item.count(), Math::addExact));
            } catch (RuntimeException exception) {
                errors.add("inventory Redis ledger " + reservation.orderId() + " items are invalid");
            }
        }

        List<SkuStock> stocks = stockMapper.selectAll();
        List<ReconciliationItem> differences = new ArrayList<>();
        for (SkuStock stock : stocks) {
            int pendingCount = pending.getOrDefault(stock.getSkuId(), 0);
            int expected = Math.subtractExact(stock.getStock(), pendingCount);
            OptionalLong actual = redisInventoryService.readStock(stock.getSkuId());
            String status = actual.isEmpty() ? "REDIS_KEY_MISSING"
                    : actual.getAsLong() == expected ? "CONSISTENT" : "DIFFERENT";
            if (!"CONSISTENT".equals(status)) {
                differences.add(new ReconciliationItem(stock.getSkuId(), stock.getStock(), pendingCount,
                        expected, actual.isPresent() ? actual.getAsLong() : null, status));
            }
            pending.remove(stock.getSkuId());
        }
        pending.forEach((skuId, count) -> errors.add(
                "outstanding command references missing MySQL SKU " + skuId + " (quantity " + count + ")"));
        return new ReconciliationReport(LocalDateTime.now(), stocks.size(), differences, errors);
    }

    public record ReconciliationReport(LocalDateTime checkedAt, int checkedSkuCount,
                                       List<ReconciliationItem> differences, List<String> errors) { }

    public record ReconciliationItem(Long skuId, int mysqlStock, int pendingRedisDeduction,
                                     int expectedRedisStock, Long actualRedisStock, String status) { }
}
