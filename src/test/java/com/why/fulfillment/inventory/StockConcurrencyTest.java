package com.why.fulfillment.inventory;

import com.zaxxer.hikari.HikariDataSource;
import com.why.fulfillment.inventory.entity.SkuStock;
import com.why.fulfillment.inventory.mapper.SkuStockMapper;
import com.why.fulfillment.inventory.service.StockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import javax.sql.DataSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 超卖对照测试 —— 这是 M0 的正确性量具。
 *
 * ============================================================
 *  这个文件是"量具"，已经写好，你不用改。
 *  你要写的是它测量的对象：StockServiceImpl 里那两个方法。
 * ============================================================
 *
 * 核心指标定义（记住这个口径，面试会被追问）：
 *
 *   成功订单数 successCount  —— 服务返回 true 的次数
 *   实际扣减量 actualDeducted = 初始库存 - 结束时可售库存
 *   超卖件数   oversold = successCount - actualDeducted
 *
 * 为什么不是"看库存有没有变负"？因为"先查后扣"用的是绝对值写入，
 * 两个线程都读到 100、都写回 99，库存不会变负，但你卖出去了 2 件只扣了 1 件。
 * 库存看起来很正常，货已经超卖了——这正是这类 bug 难被发现的原因。
 *
 * 跑之前：docker compose up -d，确认 MySQL 已就绪。
 */
@SpringBootTest(properties = {
        "fulfillment.scheduling.enabled=false",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl"
})
@DisplayName("库存扣减并发正确性")
class StockConcurrencyTest {

    /** 被抢的热点 SKU */
    private static final long HOT_SKU = 1001L;
    /** 初始库存 */
    private static final int INIT_STOCK = 100;
    /** 并发线程数：远大于库存，才能把竞态放大到必现 */
    private static final int THREADS = 300;
    /** 每单买几件 */
    private static final int COUNT_PER_ORDER = 1;

    @Autowired
    private StockService stockService;

    @Autowired
    private SkuStockMapper skuStockMapper;

    @Autowired
    private DataSource dataSource;

    @MockBean
    private RedissonClient redissonClient;

    @BeforeEach
    void resetStock() {
        SkuStock s = new SkuStock();
        s.setSkuId(HOT_SKU);
        s.setStock(INIT_STOCK);
        s.setLockStock(0);
        skuStockMapper.updateById(s);
    }

    @AfterEach
    void restoreStock() {
        resetStock();
        SkuStock restored = skuStockMapper.selectById(HOT_SKU);
        assertEquals(INIT_STOCK, restored.getStock(), "测试结束后可售库存必须恢复");
        assertEquals(0, restored.getLockStock(), "测试结束后锁定库存必须清零");
    }

    @Test
    @DisplayName("方案A 先查后扣：应当出现超卖")
    void naive_shouldOversell() throws Exception {
        Result r = race(stockService::deductNaive);
        r.print("方案A 先查后扣");

        // 这个断言故意写成"应该超卖"。它通过 = 你成功复现了 bug。
        // 如果它失败了（没超卖），先别高兴，多半是并发不够或实现里不小心加了条件判断。
        assertTrue(r.oversold() > 0,
                "没有复现超卖。检查：deductNaive 是否真的是'先读再按绝对值写'？线程数够不够？");
    }

    @Test
    @DisplayName("方案A 事务包裹先查后扣：仍应出现超卖")
    void naiveInTransaction_shouldOversell() throws Exception {
        Result r = race(stockService::deductNaiveInTransaction);
        r.print("方案A（事务包裹）先查后扣");

        assertTrue(r.oversold() > 0,
                "事务包裹后没有复现超卖；事务不能替代带条件的原子扣减");
    }

    @Test
    @DisplayName("方案B 原子SQL：超卖必须为零")
    void atomic_shouldNotOversell() throws Exception {
        Result r = race(stockService::deductAtomic);
        r.print("方案B 原子SQL");

        assertEquals(0, r.oversold(), "原子扣减仍然超卖，说明 SQL 的 where 条件没写对");
        assertEquals(INIT_STOCK, r.successCount.get(), "成功订单数应恰好等于初始库存");
        assertEquals(0, r.finalStock, "可售库存应当刚好扣完");
        assertEquals(INIT_STOCK, r.finalLockStock, "扣掉的应当全部进入锁定库存");
    }

    // ------------------------------------------------------------------
    // 并发跑马场：THREADS 个线程在同一瞬间放出去
    // ------------------------------------------------------------------
    private Result race(BiFunction<Long, Integer, Boolean> deduct) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        // startGate：所有线程都就位后一起放行，保证是"同时"而不是"陆续"
        CountDownLatch startGate = new CountDownLatch(1);
        // endGate：主线程等所有子线程跑完
        CountDownLatch endGate = new CountDownLatch(THREADS);

        AtomicInteger success = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        AtomicInteger error = new AtomicInteger();

        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    if (deduct.apply(HOT_SKU, COUNT_PER_ORDER)) {
                        success.incrementAndGet();
                    } else {
                        fail.incrementAndGet();
                    }
                } catch (Exception e) {
                    // 死锁、约束冲突都会掉到这里。单独计数，不要混进 fail。
                    error.incrementAndGet();
                } finally {
                    endGate.countDown();
                }
            });
        }

        long t0 = System.currentTimeMillis();
        startGate.countDown();
        boolean finished;
        try {
            finished = endGate.await(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        long elapsed = System.currentTimeMillis() - t0;
        assertTrue(finished, "60 秒内未跑完，可能死锁或连接池耗尽");
        assertEquals(THREADS, success.get() + fail.get() + error.get(),
                "每个并发任务都必须落入成功、库存不足或异常中的一类");

        SkuStock after = skuStockMapper.selectById(HOT_SKU);
        return new Result(success, fail, error, after.getStock(), after.getLockStock(),
                connectionPoolMaxSize(), elapsed);
    }

    private int connectionPoolMaxSize() {
        if (dataSource instanceof HikariDataSource hikariDataSource) {
            return hikariDataSource.getMaximumPoolSize();
        }
        return -1;
    }

    private static final class Result {
        final AtomicInteger successCount;
        final AtomicInteger failCount;
        final AtomicInteger errorCount;
        final int finalStock;
        final int finalLockStock;
        final int poolMaxSize;
        final long elapsedMs;

        Result(AtomicInteger s, AtomicInteger f, AtomicInteger e,
               int finalStock, int finalLockStock, int poolMaxSize, long elapsedMs) {
            this.successCount = s;
            this.failCount = f;
            this.errorCount = e;
            this.finalStock = finalStock;
            this.finalLockStock = finalLockStock;
            this.poolMaxSize = poolMaxSize;
            this.elapsedMs = elapsedMs;
        }

        int actualDeducted() { return INIT_STOCK - finalStock; }

        /** 卖出去的 - 真扣掉的。> 0 就是超卖。 */
        int oversold() { return successCount.get() * COUNT_PER_ORDER - actualDeducted(); }

        void print(String label) {
            System.out.println("""

                    ==================== %s ====================
                    并发线程数      : %d
                    初始可售库存    : %d
                    成功订单数      : %d
                    失败(库存不足)  : %d
                    异常(死锁/约束) : %d
                    ------------------------------------------------
                    结束可售库存    : %d
                    结束锁定库存    : %d
                    实际扣减量      : %d
                    >>> 超卖件数    : %d  <<<
                    ------------------------------------------------
                    HikariCP连接池上限: %d
                    耗时            : %d ms
                    ================================================
                    """.formatted(label, THREADS, INIT_STOCK,
                    successCount.get(), failCount.get(), errorCount.get(),
                    finalStock, finalLockStock, actualDeducted(), oversold(), poolMaxSize, elapsedMs));
        }
    }
}
