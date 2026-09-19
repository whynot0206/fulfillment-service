package com.why.fulfillment.inventory.redis;

import com.why.fulfillment.inventory.entity.SkuStock;
import com.why.fulfillment.inventory.mapper.SkuStockMapper;
import com.why.fulfillment.inventory.service.StockReservationItem;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * Redis implementation of multi-SKU stock pre-deduction.
 *
 * <p>Each reserve/rollback call is one Redis Lua invocation.  The service
 * does the MySQL read only when a SKU key is absent and uses SETNX for the
 * first write, so concurrent initializers cannot replace an existing value.</p>
 */
@Service
public class RedisStockServiceImpl implements RedisStockService {

    public static final String STOCK_KEY_PREFIX = "fulfillment:stock:";

    private final StringRedisTemplate redisTemplate;
    private final SkuStockMapper skuStockMapper;
    private final DefaultRedisScript<List> reserveScript;
    private final DefaultRedisScript<List> rollbackScript;
    private final DefaultRedisScript<List> reserveOnceScript;
    private final DefaultRedisScript<List> rollbackOnceScript;
    private final DefaultRedisScript<Long> reconciliationScript;

    public RedisStockServiceImpl(StringRedisTemplate redisTemplate,
                                 SkuStockMapper skuStockMapper) {
        this.redisTemplate = redisTemplate;
        this.skuStockMapper = skuStockMapper;
        this.reserveScript = script("lua/stock_pre_deduct.lua");
        this.rollbackScript = script("lua/stock_rollback.lua");
        this.reserveOnceScript = script("lua/stock_pre_deduct_once.lua");
        this.rollbackOnceScript = script("lua/stock_rollback_once.lua");
        this.reconciliationScript = new DefaultRedisScript<>();
        this.reconciliationScript.setLocation(new ClassPathResource("lua/stock_reconciliation_cas.lua"));
        this.reconciliationScript.setResultType(Long.class);
    }

    @Override
    public RedisStockResult reserve(Map<Long, Integer> quantities) {
        NormalizedQuantities normalized = normalize(quantities);
        if (!normalized.valid()) {
            return RedisStockResult.failure(
                    RedisStockResultStatus.INVALID_REQUEST,
                    normalized.invalidSkuId(),
                    normalized.quantities());
        }

        try {
            for (Long skuId : normalized.quantities().keySet()) {
                RedisStockInitializationResult initialization = initializeIfAbsent(skuId);
                if (!initialization.isSuccess()) {
                    return RedisStockResult.failure(
                            RedisStockResultStatus.SKU_NOT_FOUND,
                            skuId,
                            normalized.quantities());
                }
            }
            return executeStockScript(reserveScript, normalized.quantities());
        } catch (RuntimeException redisOrMapperFailure) {
            return RedisStockResult.failure(
                    RedisStockResultStatus.REDIS_ERROR,
                    null,
                    normalized.quantities());
        }
    }

    @Override
    public RedisStockResult reserve(List<StockReservationItem> items) {
        return reserve(normalizeItems(items));
    }

    @Override
    public RedisStockResult rollback(Map<Long, Integer> quantities) {
        NormalizedQuantities normalized = normalize(quantities);
        if (!normalized.valid()) {
            return RedisStockResult.failure(
                    RedisStockResultStatus.INVALID_REQUEST,
                    normalized.invalidSkuId(),
                    normalized.quantities());
        }

        try {
            // Rollback must operate on keys created by a previous reserve.  It
            // intentionally does not initialize a missing key from MySQL;
            // silently creating stock here would hide an inventory drift.
            return executeStockScript(rollbackScript, normalized.quantities());
        } catch (RuntimeException redisFailure) {
            return RedisStockResult.failure(
                    RedisStockResultStatus.REDIS_ERROR,
                    null,
                    normalized.quantities());
        }
    }

    @Override
    public RedisStockResult rollback(List<StockReservationItem> items) {
        return rollback(normalizeItems(items));
    }

    @Override
    public RedisStockResult reserveOnce(String operationId, List<StockReservationItem> items) {
        return reserveOnce(operationId, normalizeItems(items));
    }

    private RedisStockResult reserveOnce(String operationId, Map<Long, Integer> quantities) {
        NormalizedQuantities normalized = normalize(quantities);
        if (!validOperationId(operationId)) {
            return RedisStockResult.failure(RedisStockResultStatus.INVALID_REQUEST,
                    null, normalized.quantities());
        }
        if (!normalized.valid()) {
            return RedisStockResult.failure(RedisStockResultStatus.INVALID_REQUEST,
                    normalized.invalidSkuId(), normalized.quantities());
        }

        try {
            for (Long skuId : normalized.quantities().keySet()) {
                if (!initializeIfAbsent(skuId).isSuccess()) {
                    return RedisStockResult.failure(RedisStockResultStatus.SKU_NOT_FOUND,
                            skuId, normalized.quantities());
                }
            }
            return executeIdempotentStockScript(reserveOnceScript,
                    reservationMarkerKey(operationId), compensationMarkerKey(operationId),
                    normalized.quantities());
        } catch (RuntimeException redisOrMapperFailure) {
            return RedisStockResult.failure(RedisStockResultStatus.REDIS_ERROR,
                    null, normalized.quantities());
        }
    }

    @Override
    public RedisStockResult rollbackOnce(String operationId, List<StockReservationItem> items) {
        NormalizedQuantities normalized = normalize(normalizeItems(items));
        if (!validOperationId(operationId)) {
            return RedisStockResult.failure(RedisStockResultStatus.INVALID_REQUEST,
                    null, normalized.quantities());
        }
        if (!normalized.valid()) {
            return RedisStockResult.failure(RedisStockResultStatus.INVALID_REQUEST,
                    normalized.invalidSkuId(), normalized.quantities());
        }

        try {
            return executeIdempotentStockScript(rollbackOnceScript,
                    compensationMarkerKey(operationId), reservationMarkerKey(operationId),
                    normalized.quantities());
        } catch (RuntimeException redisFailure) {
            return RedisStockResult.failure(RedisStockResultStatus.REDIS_ERROR,
                    null, normalized.quantities());
        }
    }

    @Override
    public RedisStockInitializationResult initializeIfAbsent(Long skuId) {
        if (skuId == null || skuId <= 0) {
            return new RedisStockInitializationResult(null, skuId, false, false, null);
        }

        String key = stockKey(skuId);
        Boolean present = redisTemplate.hasKey(key);
        if (Boolean.TRUE.equals(present)) {
            return new RedisStockInitializationResult(
                    key, skuId, false, true, parseInteger(redisTemplate.opsForValue().get(key)));
        }

        SkuStock stock = skuStockMapper.selectById(skuId);
        if (stock == null || stock.getStock() == null || stock.getStock() < 0) {
            return new RedisStockInitializationResult(key, skuId, false, false, null);
        }

        Boolean initialized = redisTemplate.opsForValue().setIfAbsent(
                key, Integer.toString(stock.getStock()));
        if (Boolean.TRUE.equals(initialized)) {
            return new RedisStockInitializationResult(
                    key, skuId, true, false, stock.getStock());
        }

        // Another initializer won the SETNX race.  Report the value that won
        // rather than the stale value read from MySQL by this caller.
        return new RedisStockInitializationResult(
                key, skuId, false, true, parseInteger(redisTemplate.opsForValue().get(key)));
    }

    @Override
    public Map<Long, RedisStockInitializationResult> initializeIfAbsent(Collection<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, RedisStockInitializationResult> results = new LinkedHashMap<>();
        for (Long skuId : skuIds) {
            results.put(skuId, initializeIfAbsent(skuId));
        }
        return Collections.unmodifiableMap(results);
    }

    @Override
    public OptionalLong readStock(Long skuId) {
        if (skuId == null || skuId <= 0) {
            return OptionalLong.empty();
        }
        String value = redisTemplate.opsForValue().get(stockKey(skuId));
        if (value == null) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(value));
        } catch (NumberFormatException malformedValue) {
            return OptionalLong.empty();
        }
    }

    @Override
    public boolean compareAndSetStockForReconciliation(Long skuId,
                                                       int expectedStock,
                                                       int correctedStock) {
        if (skuId == null || skuId <= 0 || expectedStock < 0 || correctedStock < 0) {
            return false;
        }
        Long result = redisTemplate.execute(
                reconciliationScript,
                List.of(stockKey(skuId)),
                Integer.toString(expectedStock),
                Integer.toString(correctedStock));
        return result != null && result == 1L;
    }

    @Override
    public String stockKey(Long skuId) {
        if (skuId == null || skuId <= 0) {
            throw new IllegalArgumentException("skuId must be positive");
        }
        return STOCK_KEY_PREFIX + skuId;
    }

    private RedisStockResult executeStockScript(DefaultRedisScript<List> script,
                                                Map<Long, Integer> quantities) {
        List<String> keys = new ArrayList<>(quantities.size());
        List<String> arguments = new ArrayList<>(quantities.size());
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            keys.add(stockKey(entry.getKey()));
            arguments.add(Integer.toString(entry.getValue()));
        }

        List<?> result = redisTemplate.execute(script, keys, arguments.toArray());
        return parseStockScriptResult(result, quantities, false);
    }

    private RedisStockResult executeIdempotentStockScript(DefaultRedisScript<List> script,
                                                          String markerKey,
                                                          String reservationMarkerKey,
                                                          Map<Long, Integer> quantities) {
        List<String> keys = new ArrayList<>(quantities.size() + 1);
        keys.add(markerKey);
        if (reservationMarkerKey != null) {
            keys.add(reservationMarkerKey);
        }
        List<String> arguments = new ArrayList<>(quantities.size());
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            arguments.add(Integer.toString(entry.getValue()));
        }
        for (Long skuId : quantities.keySet()) {
            keys.add(stockKey(skuId));
        }
        arguments.add(signature(quantities));

        List<?> result = redisTemplate.execute(script, keys, arguments.toArray());
        return parseStockScriptResult(result, quantities, true);
    }

    private RedisStockResult parseStockScriptResult(List<?> result,
                                                    Map<Long, Integer> quantities,
                                                    boolean idempotent) {
        if (result == null || result.size() < 3) {
            return RedisStockResult.failure(RedisStockResultStatus.REDIS_ERROR, null, quantities);
        }

        long success = number(result.get(0));
        if (success == 1 || (idempotent && success == 2)) {
            return RedisStockResult.success(quantities);
        }

        int failedIndex = (int) number(result.get(1));
        Long failedSkuId = failedIndex > 0 && failedIndex <= quantities.size()
                ? new ArrayList<>(quantities.keySet()).get(failedIndex - 1)
                : null;
        RedisStockResultStatus status = switch ((int) number(result.get(2))) {
            case 1 -> RedisStockResultStatus.INSUFFICIENT_STOCK;
            case 2 -> RedisStockResultStatus.SKU_NOT_FOUND;
            case 3 -> RedisStockResultStatus.INVALID_REQUEST;
            case 4 -> RedisStockResultStatus.IDEMPOTENCY_CONFLICT;
            default -> RedisStockResultStatus.REDIS_ERROR;
        };
        return RedisStockResult.failure(status, failedSkuId, quantities);
    }

    private static DefaultRedisScript<List> script(String path) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(List.class);
        return script;
    }

    private static boolean validOperationId(String operationId) {
        return operationId != null && !operationId.isBlank();
    }

    public static String reservationMarkerKey(String operationId) {
        return STOCK_KEY_PREFIX + "reservation:" + operationId;
    }

    public static String compensationMarkerKey(String operationId) {
        return STOCK_KEY_PREFIX + "compensation:" + operationId;
    }

    private static String signature(Map<Long, Integer> quantities) {
        StringBuilder signature = new StringBuilder();
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            if (signature.length() > 0) {
                signature.append(',');
            }
            signature.append(entry.getKey()).append(':').append(entry.getValue());
        }
        return signature.toString();
    }

    private static Map<Long, Integer> normalizeItems(List<StockReservationItem> items) {
        if (items == null || items.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> merged = new TreeMap<>();
        for (StockReservationItem item : items) {
            if (item == null || item.skuId() == null || item.skuId() <= 0 || item.count() <= 0) {
                return Map.of();
            }
            try {
                merged.merge(item.skuId(), item.count(), Math::addExact);
            } catch (ArithmeticException overflow) {
                return Map.of();
            }
        }
        return merged;
    }

    private static NormalizedQuantities normalize(Map<Long, Integer> quantities) {
        if (quantities == null || quantities.isEmpty()) {
            return new NormalizedQuantities(false, new TreeMap<>(), null);
        }
        Map<Long, Integer> normalized = new TreeMap<>();
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            Long skuId = entry.getKey();
            Integer quantity = entry.getValue();
            if (skuId == null || skuId <= 0 || quantity == null || quantity <= 0) {
                return new NormalizedQuantities(false, normalized, skuId);
            }
            try {
                normalized.merge(skuId, quantity, Math::addExact);
            } catch (ArithmeticException overflow) {
                return new NormalizedQuantities(false, normalized, skuId);
            }
        }
        return new NormalizedQuantities(true, normalized, null);
    }

    private static Integer parseInteger(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof byte[] bytes) {
            return Long.parseLong(new String(bytes));
        }
        return Long.parseLong(String.valueOf(value));
    }

    private record NormalizedQuantities(boolean valid,
                                        Map<Long, Integer> quantities,
                                        Long invalidSkuId) {
    }
}
