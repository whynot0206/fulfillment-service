package com.why.fulfillment.inventory.redis;

import com.why.fulfillment.inventory.service.RedisInventoryService;
import com.why.fulfillment.inventory.service.StockReservationItem;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Adapter consumed by the asynchronous order persistence path.
 *
 * <p>Order id is used as the Redis operation id.  Both Lua marker writes and
 * stock mutations therefore happen atomically, making retries safe across a
 * crash between compensation and the MySQL dead-letter update.</p>
 */
@Service
public class RedisInventoryServiceAdapter implements RedisInventoryService {

    private final RedisStockService redisStockService;

    public RedisInventoryServiceAdapter(RedisStockService redisStockService) {
        this.redisStockService = redisStockService;
    }

    @Override
    public boolean preDeduct(Long orderId, List<StockReservationItem> items) {
        if (orderId == null) {
            return false;
        }
        RedisStockResult result = redisStockService.reserveOnce(orderId.toString(), items);
        return result.success();
    }

    @Override
    public void compensate(Long orderId, List<StockReservationItem> items) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId is required for Redis compensation");
        }
        RedisStockResult result = redisStockService.rollbackOnce(orderId.toString(), items);
        if (!result.success()) {
            throw new IllegalStateException("Redis compensation failed for order "
                    + orderId + ": " + result.status());
        }
    }
}
