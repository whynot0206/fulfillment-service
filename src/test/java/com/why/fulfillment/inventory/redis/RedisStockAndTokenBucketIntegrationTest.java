package com.why.fulfillment.inventory.redis;

import com.why.fulfillment.inventory.entity.SkuStock;
import com.why.fulfillment.inventory.mapper.SkuStockMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cycle 5 acceptance against the real Redis service on 127.0.0.1:6379.
 *
 * <p>This test deliberately does not use an embedded or mocked Redis.  Every
 * test deletes the namespaced keys in {@link #cleanUp()} so a failed assertion
 * cannot leave test stock or rate-limit state behind.</p>
 */
@DisplayName("周期 5 Redis 多 SKU 预扣与令牌桶")
class RedisStockAndTokenBucketIntegrationTest {

    private static final long SKU_A = 7_510_001L;
    private static final long SKU_B = 7_510_002L;

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static RedisStockServiceImpl stockService;
    private static RedisTokenBucketServiceImpl tokenBucketService;
    private static SkuStockMapper stockMapper;

    @BeforeAll
    static void connectToLocalRedis() {
        connectionFactory = new LettuceConnectionFactory("127.0.0.1", 6379);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        RedisCallback<String> ping = connection -> connection.ping();
        assertEquals("PONG", redisTemplate.execute(ping));

        stockMapper = mock(SkuStockMapper.class);
        stockService = new RedisStockServiceImpl(redisTemplate, stockMapper);
        tokenBucketService = new RedisTokenBucketServiceImpl(redisTemplate);
    }

    @BeforeEach
    void resetKeys() {
        cleanUp();
        when(stockMapper.selectById(SKU_A)).thenReturn(stock(SKU_A, 5));
        when(stockMapper.selectById(SKU_B)).thenReturn(stock(SKU_B, 1));
    }

    @AfterEach
    void cleanUp() {
        if (redisTemplate != null) {
            redisTemplate.delete(List.of(
                    stockService.stockKey(SKU_A),
                    stockService.stockKey(SKU_B),
                    RedisStockServiceImpl.reservationMarkerKey("cycle5-idempotent"),
                    RedisStockServiceImpl.compensationMarkerKey("cycle5-idempotent"),
                    tokenBucketService.bucketKey("cycle5-concurrency"),
                    tokenBucketService.bucketKey("cycle5-refill")));
        }
    }

    @AfterAll
    static void closeLocalRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    @DisplayName("多 SKU 先全部校验，失败时一个 key 也不扣；成功后能等量回补")
    void reserveValidatesAllSkusBeforeDeductingAndRollsBackExactly() {
        RedisStockResult rejected = stockService.reserve(Map.of(SKU_A, 3, SKU_B, 2));

        assertFalse(rejected.success());
        assertEquals(RedisStockResultStatus.INSUFFICIENT_STOCK, rejected.status());
        assertEquals(SKU_B, rejected.failedSkuId());
        assertEquals("5", redisTemplate.opsForValue().get(stockService.stockKey(SKU_A)));
        assertEquals("1", redisTemplate.opsForValue().get(stockService.stockKey(SKU_B)));

        RedisStockResult reserved = stockService.reserve(Map.of(SKU_A, 3, SKU_B, 1));
        assertTrue(reserved.success());
        assertEquals("2", redisTemplate.opsForValue().get(stockService.stockKey(SKU_A)));
        assertEquals("0", redisTemplate.opsForValue().get(stockService.stockKey(SKU_B)));

        RedisStockResult rolledBack = stockService.rollback(Map.of(SKU_A, 3, SKU_B, 1));
        assertTrue(rolledBack.success());
        assertEquals("5", redisTemplate.opsForValue().get(stockService.stockKey(SKU_A)));
        assertEquals("1", redisTemplate.opsForValue().get(stockService.stockKey(SKU_B)));
    }

    @Test
    @DisplayName("首次初始化从 MySQL 读取，后续调用不会覆盖 Redis 已有值")
    void initializationUsesSetIfAbsent() {
        RedisStockInitializationResult initialized = stockService.initializeIfAbsent(SKU_A);
        assertTrue(initialized.initialized());
        assertEquals(5, initialized.stock());

        redisTemplate.opsForValue().set(stockService.stockKey(SKU_A), "2");
        when(stockMapper.selectById(SKU_A)).thenReturn(stock(SKU_A, 99));

        RedisStockInitializationResult existing = stockService.initializeIfAbsent(SKU_A);
        assertFalse(existing.initialized());
        assertTrue(existing.alreadyPresent());
        assertEquals(2, existing.stock());
        assertEquals("2", redisTemplate.opsForValue().get(stockService.stockKey(SKU_A)));
    }

    @Test
    @DisplayName("相同 orderId 的重复预扣/补偿幂等，数量变化明确拒绝")
    void operationMarkersRejectDifferentItemSignature() {
        List<com.why.fulfillment.inventory.service.StockReservationItem> firstItems =
                List.of(new com.why.fulfillment.inventory.service.StockReservationItem(SKU_A, 1L, 3));
        List<com.why.fulfillment.inventory.service.StockReservationItem> differentItems =
                List.of(new com.why.fulfillment.inventory.service.StockReservationItem(SKU_A, 1L, 2));

        RedisStockResult first = stockService.reserveOnce("cycle5-idempotent", firstItems);
        assertTrue(first.success());
        assertTrue(stockService.reserveOnce("cycle5-idempotent", firstItems).success());

        RedisStockResult conflict = stockService.reserveOnce("cycle5-idempotent", differentItems);
        assertFalse(conflict.success());
        assertEquals(RedisStockResultStatus.IDEMPOTENCY_CONFLICT, conflict.status());
        assertEquals("2", redisTemplate.opsForValue().get(stockService.stockKey(SKU_A)));

        RedisStockResult compensated = stockService.rollbackOnce("cycle5-idempotent", firstItems);
        assertTrue(compensated.success());
        assertTrue(stockService.rollbackOnce("cycle5-idempotent", firstItems).success());
        RedisStockResult compensationConflict =
                stockService.rollbackOnce("cycle5-idempotent", differentItems);
        assertFalse(compensationConflict.success());
        assertEquals(RedisStockResultStatus.IDEMPOTENCY_CONFLICT, compensationConflict.status());
        assertEquals("5", redisTemplate.opsForValue().get(stockService.stockKey(SKU_A)));

        RedisStockResult secondReservation = stockService.reserveOnce("cycle5-idempotent", firstItems);
        assertTrue(secondReservation.success());
        RedisStockResult secondCompensation = stockService.rollbackOnce("cycle5-idempotent", firstItems);
        assertTrue(secondCompensation.success());
        assertEquals("5", redisTemplate.opsForValue().get(stockService.stockKey(SKU_A)));
    }

    @Test
    @DisplayName("库存读取能区分不存在，CAS 修正不会覆盖并发更新")
    void stockReadAndReconciliationCasAreSafe() {
        assertTrue(stockService.readStock(SKU_A).isEmpty());
        redisTemplate.opsForValue().set(stockService.stockKey(SKU_A), "5");
        assertEquals(5, stockService.readStock(SKU_A).orElseThrow());

        assertFalse(stockService.compareAndSetStockForReconciliation(SKU_A, 4, 9));
        assertEquals("5", redisTemplate.opsForValue().get(stockService.stockKey(SKU_A)));
        assertTrue(stockService.compareAndSetStockForReconciliation(SKU_A, 5, 9));
        assertEquals("9", redisTemplate.opsForValue().get(stockService.stockKey(SKU_A)));
    }

    @Test
    @DisplayName("令牌桶的并发请求按容量原子裁决")
    void tokenBucketIsAtomicForConcurrentRequests() throws Exception {
        String bucket = "cycle5-concurrency";
        int callers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RedisTokenBucketResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return tokenBucketService.tryAcquire(bucket, 5, 0, 1);
                }));
            }
            start.countDown();

            long allowed = 0;
            for (Future<RedisTokenBucketResult> future : futures) {
                if (future.get().allowed()) {
                    allowed++;
                }
            }
            assertEquals(5, allowed);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("令牌桶按补充速率恢复令牌并支持一次请求多个令牌")
    void tokenBucketRefillsAndSupportsRequestedTokenCount() throws Exception {
        String bucket = "cycle5-refill";
        RedisTokenBucketResult first = tokenBucketService.tryAcquire(bucket, 3, 20, 3);
        assertTrue(first.allowed(), first.toString());
        assertEquals(0, first.remainingTokens().compareTo(java.math.BigDecimal.ZERO));

        RedisTokenBucketResult rejected = tokenBucketService.tryAcquire(bucket, 3, 20, 2);
        assertFalse(rejected.allowed());

        Thread.sleep(150);
        RedisTokenBucketResult refilled = tokenBucketService.tryAcquire(bucket, 3, 20, 2);
        assertTrue(refilled.allowed(), refilled.toString()
                + "; 20 tokens/second should refill at least two tokens in 150ms");
        assertTrue(refilled.remainingTokens().signum() >= 0);
    }

    private static SkuStock stock(long skuId, int available) {
        SkuStock stock = new SkuStock();
        stock.setSkuId(skuId);
        stock.setStock(available);
        stock.setLockStock(0);
        return stock;
    }
}
