package com.why.fulfillment.inventory.redis;

import com.why.fulfillment.api.inventory.InventoryReserveItem;
import com.why.fulfillment.inventory.entity.SkuStock;
import com.why.fulfillment.inventory.mapper.SkuStockMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * Multi-SKU Redis inventory implementation.
 *
 * <p>The Lua scripts validate every SKU before mutating any key. The MySQL
 * read is only used to lazily initialize an absent Redis key, and SETNX keeps
 * concurrent initializers from replacing a value already created by another
 * request.</p>
 */
@Service
public class RedisStockServiceImpl implements RedisStockService {

    public static final String STOCK_KEY_PREFIX = "fulfillment:stock:";
    private static final String RESERVATION_KEY_PREFIX = STOCK_KEY_PREFIX + "reservation:";
    private static final String COMPENSATION_KEY_PREFIX = STOCK_KEY_PREFIX + "compensation:";
    private static final long MARKER_TTL_SECONDS = 604800L;

    private final StringRedisTemplate redisTemplate;
    private final SkuStockMapper skuStockMapper;
    private final DefaultRedisScript<List> reserveScript;
    private final DefaultRedisScript<List> compensateScript;
    private final DefaultRedisScript<List> compensateIfReservedScript;

    public RedisStockServiceImpl(StringRedisTemplate redisTemplate,
                                 SkuStockMapper skuStockMapper) {
        this.redisTemplate = redisTemplate;
        this.skuStockMapper = skuStockMapper;
        this.reserveScript = script("lua/redis_reserve_once.lua");
        this.compensateScript = script("lua/redis_compensate_once.lua");
        this.compensateIfReservedScript = script("lua/redis_compensate_if_reserved.lua");
    }

    @Override
    public RedisStockResult reserve(Long orderId, List<InventoryReserveItem> items) {
        NormalizedItems normalized = normalize(orderId, items);
        if (!normalized.valid()) {
            return result(RedisStockResultStatus.REJECTED, normalized.error(), normalized.quantities());
        }

        try {
            RedisStockResult markerResult = inspectMarkers(orderId, normalized.signature(), true,
                    normalized.quantities());
            if (markerResult != null) {
                return markerResult;
            }
            for (Long skuId : normalized.quantities().keySet()) {
                Initialization initialization = initializeIfAbsent(skuId);
                if (!initialization.success()) {
                    return result(RedisStockResultStatus.REJECTED,
                            initialization.error(), normalized.quantities());
                }
            }
            return execute(reserveScript, reservationMarkerKey(orderId), compensationMarkerKey(orderId),
                    normalized, Operation.RESERVE);
        } catch (RuntimeException exception) {
            return result(RedisStockResultStatus.UNKNOWN,
                    message(exception, "Redis reservation failed"), normalized.quantities());
        }
    }

    @Override
    public RedisStockResult compensate(Long orderId, List<InventoryReserveItem> items) {
        NormalizedItems normalized = normalize(orderId, items);
        if (!normalized.valid()) {
            return result(RedisStockResultStatus.REJECTED, normalized.error(), normalized.quantities());
        }
        try {
            return execute(compensateScript, compensationMarkerKey(orderId),
                    reservationMarkerKey(orderId), normalized, Operation.COMPENSATE);
        } catch (RuntimeException exception) {
            return result(RedisStockResultStatus.UNKNOWN,
                    message(exception, "Redis compensation failed"), normalized.quantities());
        }
    }

    @Override
    public RedisStockResult compensateIfReserved(Long orderId, List<InventoryReserveItem> items) {
        NormalizedItems normalized = normalize(orderId, items);
        if (!normalized.valid()) {
            return result(RedisStockResultStatus.REJECTED, normalized.error(), normalized.quantities());
        }
        try {
            return execute(compensateIfReservedScript, compensationMarkerKey(orderId),
                    reservationMarkerKey(orderId), normalized, Operation.COMPENSATE_IF_RESERVED);
        } catch (RuntimeException exception) {
            return result(RedisStockResultStatus.UNKNOWN,
                    message(exception, "Redis conditional compensation failed"), normalized.quantities());
        }
    }

    @Override
    public OptionalLong readStock(Long skuId) {
        if (skuId == null || skuId <= 0) {
            return OptionalLong.empty();
        }
        try {
            String value = redisTemplate.opsForValue().get(stockKey(skuId));
            if (value == null) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(Long.parseLong(value));
        } catch (RuntimeException ignored) {
            return OptionalLong.empty();
        }
    }

    @Override
    public String stockKey(Long skuId) {
        if (skuId == null || skuId <= 0) {
            throw new IllegalArgumentException("skuId must be positive");
        }
        return STOCK_KEY_PREFIX + skuId;
    }

    public static String reservationMarkerKey(String orderId) {
        return RESERVATION_KEY_PREFIX + requireOrderId(orderId);
    }

    public static String compensationMarkerKey(String orderId) {
        return COMPENSATION_KEY_PREFIX + requireOrderId(orderId);
    }

    public static String reservationMarkerKey(Long orderId) {
        return reservationMarkerKey(orderId == null ? null : orderId.toString());
    }

    public static String compensationMarkerKey(Long orderId) {
        return compensationMarkerKey(orderId == null ? null : orderId.toString());
    }

    private RedisStockResult inspectMarkers(Long orderId,
                                            String signature,
                                            boolean reserve,
                                            Map<Long, Integer> quantities) {
        String reservation = redisTemplate.opsForValue().get(reservationMarkerKey(orderId));
        String compensation = redisTemplate.opsForValue().get(compensationMarkerKey(orderId));
        if (compensation != null) {
            if (compensation.equals(signature)) {
                return result(reserve ? RedisStockResultStatus.CANCELED
                                : RedisStockResultStatus.ALREADY_COMPENSATED,
                        reserve ? "order was canceled before reservation completed" : null, quantities);
            }
            return result(RedisStockResultStatus.CONFLICT,
                    "order " + orderId + " was used with a different inventory payload", quantities);
        }
        if (reservation != null) {
            if (reservation.equals(signature)) {
                return result(RedisStockResultStatus.ALREADY_RESERVED, null, quantities);
            }
            return result(RedisStockResultStatus.CONFLICT,
                    "order " + orderId + " was reserved with a different inventory payload", quantities);
        }
        return null;
    }

    private RedisStockResult execute(DefaultRedisScript<List> script,
                                     String markerKey,
                                     String otherMarkerKey,
                                     NormalizedItems normalized,
                                     Operation operation) {
        List<String> keys = new ArrayList<>(normalized.quantities().size() + 2);
        keys.add(markerKey);
        keys.add(otherMarkerKey);
        List<String> arguments = new ArrayList<>(normalized.quantities().size() + 1);
        for (Integer quantity : normalized.quantities().values()) {
            arguments.add(Integer.toString(quantity));
        }
        arguments.add(normalized.signature());
        for (Long skuId : normalized.quantities().keySet()) {
            keys.add(stockKey(skuId));
        }
        List<?> raw = redisTemplate.execute(script, keys, arguments.toArray());
        return parse(raw, normalized, operation);
    }

    private RedisStockResult parse(List<?> raw,
                                   NormalizedItems normalized,
                                   Operation operation) {
        if (raw == null || raw.isEmpty()) {
            return result(RedisStockResultStatus.UNKNOWN, "Redis script returned no result",
                    normalized.quantities());
        }
        long code = number(raw.get(0));
        if (operation == Operation.RESERVE) {
            return switch ((int) code) {
                case 1 -> result(RedisStockResultStatus.RESERVED, null, normalized.quantities());
                case 2 -> result(RedisStockResultStatus.ALREADY_RESERVED, null, normalized.quantities());
                case 3 -> result(RedisStockResultStatus.CANCELED,
                        "order was canceled before reservation completed", normalized.quantities());
                case 4 -> result(RedisStockResultStatus.CONFLICT,
                        "order was used with a different inventory payload", normalized.quantities());
                default -> rejectedScriptResult(raw, normalized);
            };
        }
        if (operation == Operation.COMPENSATE_IF_RESERVED && code == 5) {
            return result(RedisStockResultStatus.NO_RESERVATION, null, normalized.quantities());
        }
        return switch ((int) code) {
            case 1 -> result(RedisStockResultStatus.COMPENSATED, null, normalized.quantities());
            case 2 -> result(RedisStockResultStatus.ALREADY_COMPENSATED, null, normalized.quantities());
            case 3 -> result(RedisStockResultStatus.CONFLICT,
                    "order was used with a different inventory payload", normalized.quantities());
            default -> result(RedisStockResultStatus.UNKNOWN,
                    scriptFailureMessage(raw, normalized), normalized.quantities());
        };
    }

    private RedisStockResult rejectedScriptResult(List<?> raw, NormalizedItems normalized) {
        return result(RedisStockResultStatus.REJECTED,
                scriptFailureMessage(raw, normalized), normalized.quantities());
    }

    private String scriptFailureMessage(List<?> raw, NormalizedItems normalized) {
        if (raw.size() < 3) {
            return "Redis inventory script failed";
        }
        int index = (int) number(raw.get(1));
        Long skuId = index > 0 && index <= normalized.quantities().size()
                ? new ArrayList<>(normalized.quantities().keySet()).get(index - 1) : null;
        int reason = (int) number(raw.get(2));
        String detail = switch (reason) {
            case 1 -> "insufficient stock";
            case 2 -> "Redis stock key is missing";
            case 3 -> "invalid inventory quantity";
            default -> "Redis inventory script failed";
        };
        return skuId == null ? detail : detail + " for sku " + skuId;
    }

    private Initialization initializeIfAbsent(Long skuId) {
        String key = stockKey(skuId);
        Boolean present = redisTemplate.hasKey(key);
        if (Boolean.TRUE.equals(present)) {
            String existing = redisTemplate.opsForValue().get(key);
            if (parseNonNegative(existing) == null) {
                return Initialization.failed("Redis stock value is invalid for sku " + skuId);
            }
            return Initialization.ok();
        }
        SkuStock stock = skuStockMapper.selectBySkuId(skuId);
        if (stock == null || stock.getStock() == null || stock.getStock() < 0) {
            return Initialization.failed("SKU does not exist: " + skuId);
        }
        Boolean initialized = redisTemplate.opsForValue().setIfAbsent(key,
                Integer.toString(stock.getStock()));
        if (Boolean.TRUE.equals(initialized)) {
            return Initialization.ok();
        }
        if (parseNonNegative(redisTemplate.opsForValue().get(key)) == null) {
            return Initialization.failed("Redis stock value is invalid for sku " + skuId);
        }
        return Initialization.ok();
    }

    private static Integer parseNonNegative(String value) {
        if (value == null) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static NormalizedItems normalize(Long orderId, List<InventoryReserveItem> items) {
        if (orderId == null || orderId <= 0) {
            return NormalizedItems.invalid("orderId must be positive");
        }
        if (items == null || items.isEmpty()) {
            return NormalizedItems.invalid("items must not be empty");
        }
        Map<Long, InventoryReserveItem> merged = new TreeMap<>();
        for (InventoryReserveItem item : items) {
            if (item == null || item.skuId() == null || item.spuId() == null
                    || item.skuId() <= 0 || item.spuId() <= 0
                    || item.count() == null || item.count() <= 0) {
                return NormalizedItems.invalid("each item must contain positive skuId, spuId and count");
            }
            InventoryReserveItem current = merged.get(item.skuId());
            if (current == null) {
                merged.put(item.skuId(), item);
                continue;
            }
            if (!current.spuId().equals(item.spuId())) {
                return NormalizedItems.invalid("same skuId must use one spuId");
            }
            try {
                merged.put(item.skuId(), new InventoryReserveItem(item.skuId(), item.spuId(),
                        Math.addExact(current.count(), item.count())));
            } catch (ArithmeticException overflow) {
                return NormalizedItems.invalid("reservation count is too large");
            }
        }
        StringBuilder signature = new StringBuilder();
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (InventoryReserveItem item : merged.values()) {
            if (signature.length() > 0) {
                signature.append(',');
            }
            signature.append(item.skuId()).append(':').append(item.spuId()).append(':').append(item.count());
            quantities.put(item.skuId(), item.count());
        }
        return new NormalizedItems(true, null, quantities, signature.toString());
    }

    private static RedisStockResult result(RedisStockResultStatus status,
                                           String error,
                                           Map<Long, Integer> quantities) {
        return RedisStockResult.of(status, error, quantities);
    }

    private static String requireOrderId(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId must not be blank");
        }
        return orderId;
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

    private static String message(RuntimeException exception, String fallback) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? fallback : exception.getMessage();
    }

    private static DefaultRedisScript<List> script(String path) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(List.class);
        return script;
    }

    private enum Operation {
        RESERVE,
        COMPENSATE,
        COMPENSATE_IF_RESERVED
    }

    private record Initialization(boolean success, String error) {
        static Initialization ok() { return new Initialization(true, null); }
        static Initialization failed(String error) { return new Initialization(false, error); }
    }

    private record NormalizedItems(boolean valid,
                                   String error,
                                   Map<Long, Integer> quantities,
                                   String signature) {
        NormalizedItems {
            quantities = quantities == null ? Collections.emptyMap() : Map.copyOf(quantities);
        }

        static NormalizedItems invalid(String error) {
            return new NormalizedItems(false, error, Map.of(), "");
        }
    }
}
