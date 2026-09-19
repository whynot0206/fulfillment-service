package com.why.fulfillment.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.why.fulfillment.inventory.entity.SkuStock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 库存 Mapper。
 *
 * ============================================================
 *  库存扣减 SQL 集中写在 src/main/resources/mapper/SkuStockMapper.xml。
 * ============================================================
 */
@Mapper
public interface SkuStockMapper extends BaseMapper<SkuStock> {

    /**
     * 【方案 A：先查后扣】的写入部分——按绝对值更新库存。
     *
     * 配合 Service 里的 "先 selectById 读出来，Java 里判断够不够，再写回去" 使用。
     * 这是错误示范，故意保留用于并发对照测试。
     *
     * 注意：where 里只有主键，没有任何条件判断——这正是它会超卖的原因。
     *
     * @return 影响行数
     */
    int updateStockAbsolute(@Param("skuId") Long skuId,
                            @Param("stock") Integer stock,
                            @Param("lockStock") Integer lockStock);

    /**
     * 【方案 B：原子扣减】把"检查"和"扣减"收敛进同一条 SQL。
     *
     * 条件判断和扣减在同一条 SQL 中完成，返回 0 行表示库存不足。
     *
     * @return 影响行数；0 表示库存不足，扣减未发生
     */
    int reduceStockAtomic(@Param("skuId") Long skuId,
                          @Param("count") Integer count);

    /** 订单超时释放预占库存。 */
    int addAvailableStock(@Param("skuId") Long skuId,
                          @Param("count") Integer count);

    /** 支付成功后把锁定库存转为实际已售库存。 */
    int consumeLockedStock(@Param("skuId") Long skuId,
                           @Param("count") Integer count);
}
