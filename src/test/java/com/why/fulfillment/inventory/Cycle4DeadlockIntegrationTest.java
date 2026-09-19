package com.why.fulfillment.inventory;

import com.why.fulfillment.inventory.entity.SkuStock;
import com.why.fulfillment.inventory.entity.SkuStockLock;
import com.why.fulfillment.inventory.mapper.SkuStockLockMapper;
import com.why.fulfillment.inventory.mapper.SkuStockMapper;
import com.why.fulfillment.inventory.service.InventoryReservationService;
import com.why.fulfillment.inventory.service.StockReservationItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 周期 4 的真实数据库验证入口。
 *
 * 两个事务分别提交相反顺序的 SKU 请求；服务内部必须先按 skuId 排序，
 * 才能让两个事务按同一顺序申请行锁。运行前需要 MySQL 已启动。
 */
@SpringBootTest(properties = {
        "fulfillment.scheduling.enabled=false",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl"
})
@DisplayName("周期 4 多 SKU 加锁顺序")
class Cycle4DeadlockIntegrationTest {

    private static final long SKU_A = 1001L;
    private static final long SKU_B = 1002L;
    private static final long BASE_ORDER_ID = 9_000_000L;
    private static final int INITIAL_STOCK = 100;
    private static final int ROUNDS = 20;

    @Autowired
    private InventoryReservationService reservationService;

    @Autowired
    private SkuStockMapper stockMapper;

    @Autowired
    private SkuStockLockMapper stockLockMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockBean
    private RedissonClient redissonClient;

    @BeforeEach
    void resetStock() {
        restoreDatabaseState();
    }

    @AfterEach
    void cleanUp() {
        restoreDatabaseState();
    }

    private void restoreDatabaseState() {
        jdbcTemplate.update("delete from sku_stock_lock where order_id >= ? and order_id < ?",
                BASE_ORDER_ID, BASE_ORDER_ID + ROUNDS * 2L);
        resetSku(SKU_A, 1L);
        resetSku(SKU_B, 1L);
    }

    @Test
    @DisplayName("未排序的反向加锁会被 MySQL 检测为死锁")
    void reverseLockOrderShouldReproduceDeadlock() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        int deadlockVictims = 0;
        try {
            for (int round = 0; round < ROUNDS; round++) {
                long orderA = BASE_ORDER_ID + round * 2;
                long orderB = orderA + 1;
                CyclicBarrier firstLockAcquired = new CyclicBarrier(2);
                Future<Void> first = pool.submit(() -> {
                    runUnorderedTransaction(transaction, orderA, SKU_A, SKU_B, firstLockAcquired);
                    return null;
                });
                Future<Void> second = pool.submit(() -> {
                    runUnorderedTransaction(transaction, orderB, SKU_B, SKU_A, firstLockAcquired);
                    return null;
                });
                deadlockVictims += List.of(failureOf(first), failureOf(second)).stream()
                        .filter(Cycle4DeadlockIntegrationTest::isDeadlock)
                        .count();
            }

            System.out.printf("周期4未排序基线：事务数=%d，死锁回滚数=%d，死锁率=%.0f%%%n",
                    ROUNDS * 2, deadlockVictims, deadlockVictims * 100.0 / (ROUNDS * 2));
            assertEquals(ROUNDS, deadlockVictims,
                    "每轮两个事务反向持有第一把锁后，应由 MySQL 选择一个死锁牺牲者");
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "基线测试线程未及时退出");
        }
    }

    @Test
    @DisplayName("反向输入顺序在统一排序后不产生死锁")
    void reverseInputOrderShouldCompleteWithoutDeadlock() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int succeeded = 0;
        try {
            for (int round = 0; round < ROUNDS; round++) {
                long orderA = BASE_ORDER_ID + round * 2;
                long orderB = orderA + 1;
                CountDownLatch start = new CountDownLatch(1);
                Future<Boolean> first = pool.submit(() -> reserveAfter(start, orderA, List.of(
                        new StockReservationItem(SKU_A, 1L, 1),
                        new StockReservationItem(SKU_B, 1L, 1))));
                Future<Boolean> second = pool.submit(() -> reserveAfter(start, orderB, List.of(
                        new StockReservationItem(SKU_B, 1L, 1),
                        new StockReservationItem(SKU_A, 1L, 1))));
                start.countDown();
                succeeded += result(first);
                succeeded += result(second);
                reservationService.release(orderA);
                reservationService.release(orderB);
            }
        } finally {
            pool.shutdownNow();
        }

        int failedTransactions = ROUNDS * 2 - succeeded;
        System.out.printf("周期4排序修复：事务数=%d，失败数=%d，死锁率=0%%%n",
                ROUNDS * 2, failedTransactions);
        assertEquals(ROUNDS * 2, succeeded,
                "存在未完成事务，失败次数=" + failedTransactions);
        assertEquals(INITIAL_STOCK, stockMapper.selectById(SKU_A).getStock());
        assertEquals(INITIAL_STOCK, stockMapper.selectById(SKU_B).getStock());
    }

    private void runUnorderedTransaction(TransactionTemplate transaction,
                                         long orderId,
                                         long firstSku,
                                         long secondSku,
                                         CyclicBarrier firstLockAcquired) {
        transaction.executeWithoutResult(status -> {
            requireStockUpdate(firstSku);
            await(firstLockAcquired);
            requireStockUpdate(secondSku);
            insertLock(orderId, firstSku);
            insertLock(orderId, secondSku);
        });
    }

    private void insertLock(long orderId, long skuId) {
        SkuStockLock lock = new SkuStockLock();
        lock.setOrderId(orderId);
        lock.setSkuId(skuId);
        lock.setSpuId(1L);
        lock.setCount(1);
        lock.setStatus(SkuStockLock.LOCKED);
        stockLockMapper.insert(lock);
    }

    private void requireStockUpdate(long skuId) {
        if (stockMapper.reduceStockAtomic(skuId, 1) != 1) {
            throw new IllegalStateException("failed to lock stock for sku " + skuId);
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while synchronizing transactions", interrupted);
        } catch (BrokenBarrierException | TimeoutException failure) {
            throw new IllegalStateException("transactions did not acquire their first locks", failure);
        }
    }

    private boolean reserveAfter(CountDownLatch start, long orderId,
                                 List<StockReservationItem> items) throws InterruptedException {
        start.await();
        reservationService.reserve(orderId, items);
        return true;
    }

    private static int result(Future<Boolean> future) {
        try {
            return future.get(30, TimeUnit.SECONDS) ? 1 : 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (ExecutionException | TimeoutException failure) {
            return 0;
        }
    }

    private static Throwable failureOf(Future<?> future) {
        try {
            future.get(30, TimeUnit.SECONDS);
            return NoFailure.INSTANCE;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return interrupted;
        } catch (ExecutionException failure) {
            return failure.getCause();
        } catch (TimeoutException timeout) {
            return timeout;
        }
    }

    private static boolean isDeadlock(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql
                    && (sql.getErrorCode() == 1213 || "40001".equals(sql.getSQLState()))) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("deadlock")) {
                return true;
            }
        }
        return false;
    }

    private void resetSku(long skuId, long spuId) {
        SkuStock stock = new SkuStock();
        stock.setSkuId(skuId);
        stock.setSpuId(spuId);
        stock.setStock(INITIAL_STOCK);
        stock.setLockStock(0);
        stockMapper.updateById(stock);
    }

    private static final class NoFailure extends RuntimeException {
        private static final NoFailure INSTANCE = new NoFailure();

        private NoFailure() {
            super("no failure", null, false, false);
        }
    }
}
