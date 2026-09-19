package com.why.fulfillment.inventory.reconciliation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.why.fulfillment.inventory.entity.SkuStock;
import com.why.fulfillment.inventory.mapper.SkuStockMapper;
import com.why.fulfillment.inventory.redis.RedisStockService;
import com.why.fulfillment.inventory.service.StockReservationItem;
import com.why.fulfillment.order.entity.AsyncOrderCommand;
import com.why.fulfillment.order.mapper.AsyncOrderCommandMapper;
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
public class StockReconciliationServiceImpl implements StockReconciliationService {

    private static final TypeReference<List<StockReservationItem>> ITEM_LIST = new TypeReference<>() { };

    private final AsyncOrderCommandMapper commandMapper;
    private final SkuStockMapper stockMapper;
    private final RedisStockService redisStockService;
    private final ObjectMapper objectMapper;

    public StockReconciliationServiceImpl(AsyncOrderCommandMapper commandMapper,
                                          SkuStockMapper stockMapper,
                                          RedisStockService redisStockService,
                                          ObjectMapper objectMapper) {
        this.commandMapper = commandMapper;
        this.stockMapper = stockMapper;
        this.redisStockService = redisStockService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StockReconciliationReport inspect() {
        Map<Long, Integer> pendingBySku = new HashMap<>();
        List<String> errors = new ArrayList<>();

        // Read commands first so this transaction's repeatable-read snapshot covers both tables.
        for (AsyncOrderCommand command : commandMapper.listOutstandingForReconciliation()) {
            try {
                for (StockReservationItem item : objectMapper.readValue(command.getItemsJson(), ITEM_LIST)) {
                    pendingBySku.merge(item.skuId(), item.count(), Math::addExact);
                }
            } catch (Exception exception) {
                errors.add("command " + command.getCommandId() + " items cannot be read: " + exception.getMessage());
            }
        }

        List<SkuStock> stocks = stockMapper.selectList(null);
        List<StockReconciliationItem> differences = new ArrayList<>();
        for (SkuStock stock : stocks) {
            int pending = pendingBySku.getOrDefault(stock.getSkuId(), 0);
            int expected = stock.getStock() - pending;
            OptionalLong actual = redisStockService.readStock(stock.getSkuId());
            StockReconciliationItem.Status status;
            Long actualValue = null;
            if (actual.isEmpty()) {
                status = StockReconciliationItem.Status.REDIS_KEY_MISSING;
            } else {
                actualValue = actual.getAsLong();
                status = actualValue == expected
                        ? StockReconciliationItem.Status.CONSISTENT
                        : StockReconciliationItem.Status.DIFFERENT;
            }
            if (status != StockReconciliationItem.Status.CONSISTENT) {
                differences.add(new StockReconciliationItem(stock.getSkuId(), stock.getStock(), pending,
                        expected, actualValue, status));
            }
            pendingBySku.remove(stock.getSkuId());
        }

        pendingBySku.forEach((skuId, pending) ->
                errors.add("outstanding command references missing MySQL SKU " + skuId + " (quantity " + pending + ")"));
        return new StockReconciliationReport(LocalDateTime.now(), stocks.size(), List.copyOf(differences),
                List.copyOf(errors));
    }
}
