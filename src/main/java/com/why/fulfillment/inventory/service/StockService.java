package com.why.fulfillment.inventory.service;

/**
 * 库存扣减服务。
 *
 * ============================================================
 * 单 SKU 扣减契约，具体实现位于 StockServiceImpl。
 *
 *  两个方法是同一件事的两种写法，M0 的核心是比较它们的并发正确性：
 *  用同一个并发测试分别打这两个方法，看出数字差异。
 * ============================================================
 */
public interface StockService {

    /**
     * 方案 A：先查后扣（错误示范，用于对照）。
     *
     * 实现步骤：
     *   1. mapper.selectById(skuId) 读出当前库存
     *   2. if (stock < count) return false;
     *   3. mapper.updateStockAbsolute(skuId, stock - count, lockStock + count)
     *   4. return true;
     *
     * 加不加 @Transactional 都会超卖。加了之后超卖形态会变（默认 RR 隔离级别下
     * 读的是快照），这个差异本身值得你测一测并记下来——面试问到"事务能不能防超卖"
     * 时，这是最有说服力的回答。
     */
    boolean deductNaive(Long skuId, int count);

    /**
     * 方案 A 的事务版对照：把先查后扣放进同一个本地事务。
     *
     * 这个方法故意保留绝对值更新，用来验证“加事务”本身不能把错误的并发写法变正确。
     */
    boolean deductNaiveInTransaction(Long skuId, int count);

    /**
     * 方案 B：原子 SQL 扣减。
     *
     * 实现步骤：
     *   1. int rows = mapper.reduceStockAtomic(skuId, count);
     *   2. return rows > 0;
     *
     * 就两行。难的不是代码量，是想清楚为什么这两行就够了。
     */
    boolean deductAtomic(Long skuId, int count);
}
