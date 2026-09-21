package com.why.fulfillment.inventory.reconciliation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.why.fulfillment.inventory.entity.SkuStock;
import com.why.fulfillment.inventory.mapper.SkuStockMapper;
import com.why.fulfillment.inventory.redis.RedisStockService;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private static final TypeReference<List<CommandItem>> ITEMS_TYPE = new TypeReference<>() { };
    private final JdbcTemplate jdbcTemplate;
    private final SkuStockMapper stockMapper;
    private final RedisStockService redisInventoryService;
    private final ObjectMapper objectMapper;

    public StockReconciliationService(JdbcTemplate jdbcTemplate, SkuStockMapper stockMapper,
                                      RedisStockService redisInventoryService, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.stockMapper = stockMapper;
        this.redisInventoryService = redisInventoryService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReconciliationReport inspect() {
        Map<Long, Integer> pending = new HashMap<>();
        List<String> errors = new ArrayList<>();
        jdbcTemplate.query("""
                SELECT command_id,items_json FROM microservice_order_command
                 WHERE redis_reserved=1 AND status IN (1,2) ORDER BY command_id
                """, rs -> {
            long commandId = rs.getLong("command_id");
            try {
                for (CommandItem item : objectMapper.readValue(rs.getString("items_json"), ITEMS_TYPE)) {
                    pending.merge(item.skuId(), item.count(), Math::addExact);
                }
            } catch (Exception exception) {
                errors.add("command " + commandId + " items cannot be read");
            }
        });

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

    private record CommandItem(Long skuId, Long spuId, Integer count, java.math.BigDecimal price) { }
}
