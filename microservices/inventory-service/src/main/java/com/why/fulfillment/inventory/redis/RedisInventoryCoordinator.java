package com.why.fulfillment.inventory.redis;

import com.why.fulfillment.api.inventory.InventoryReserveItem;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RedisInventoryCoordinator {
    private final RedisStockService stockService;
    private final InventoryRedisLedgerService ledgerService;

    public RedisInventoryCoordinator(RedisStockService stockService, InventoryRedisLedgerService ledgerService) {
        this.stockService = stockService;
        this.ledgerService = ledgerService;
    }

    public RedisStockResult reserve(Long orderId, List<InventoryReserveItem> items) {
        RedisStockResult result = stockService.reserve(orderId, items);
        if (result.status() != RedisStockResultStatus.RESERVED
                && result.status() != RedisStockResultStatus.ALREADY_RESERVED) {
            return result;
        }
        try {
            ledgerService.recordPending(orderId, items);
            return result;
        } catch (InventoryRedisLedgerService.LedgerConflictException exception) {
            return RedisStockResult.of(RedisStockResultStatus.CONFLICT, exception.getMessage(), result.quantities());
        } catch (InventoryRedisLedgerService.LedgerStateException exception) {
            return RedisStockResult.of(RedisStockResultStatus.CANCELED, exception.getMessage(), result.quantities());
        } catch (RuntimeException exception) {
            return RedisStockResult.of(RedisStockResultStatus.UNKNOWN,
                    "Redis reservation ledger is pending", result.quantities());
        }
    }

    public RedisStockResult compensate(Long orderId, List<InventoryReserveItem> items) {
        RedisStockResult result = stockService.compensate(orderId, items);
        return recordCompensation(orderId, items, result, true);
    }

    public RedisStockResult compensateIfReserved(Long orderId, List<InventoryReserveItem> items) {
        RedisStockResult result = stockService.compensateIfReserved(orderId, items);
        return recordCompensation(orderId, items, result, false);
    }

    public void materialize(Long orderId, List<InventoryReserveItem> items) {
        ledgerService.recordMaterialized(orderId, items);
    }

    private RedisStockResult recordCompensation(Long orderId, List<InventoryReserveItem> items,
                                                RedisStockResult result, boolean force) {
        if (result.status() == RedisStockResultStatus.NO_RESERVATION && !force) {
            return result;
        }
        if (result.status() != RedisStockResultStatus.COMPENSATED
                && result.status() != RedisStockResultStatus.ALREADY_COMPENSATED) {
            return result;
        }
        try {
            ledgerService.recordCompensated(orderId, items);
            return result;
        } catch (InventoryRedisLedgerService.LedgerConflictException exception) {
            return RedisStockResult.of(RedisStockResultStatus.CONFLICT, exception.getMessage(), result.quantities());
        } catch (RuntimeException exception) {
            return RedisStockResult.of(RedisStockResultStatus.UNKNOWN,
                    "Redis compensation ledger is pending", result.quantities());
        }
    }
}
