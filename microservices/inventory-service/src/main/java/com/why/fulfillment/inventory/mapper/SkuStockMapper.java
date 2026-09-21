package com.why.fulfillment.inventory.mapper;

import com.why.fulfillment.inventory.entity.SkuStock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SkuStockMapper {

    @Select("select sku_id, spu_id, stock, lock_stock, version, update_time "
            + "from sku_stock where sku_id = #{skuId}")
    SkuStock selectBySkuId(@Param("skuId") Long skuId);

    @Select("select sku_id, spu_id, stock, lock_stock, version, update_time from sku_stock order by sku_id")
    List<SkuStock> selectAll();

    /** Atomically moves available stock into the lock_stock bucket. */
    @Update("update sku_stock "
            + "set stock = stock - #{count}, lock_stock = lock_stock + #{count} "
            + "where sku_id = #{skuId} and stock >= #{count}")
    int reserve(@Param("skuId") Long skuId, @Param("count") Integer count);

    /** Returns a reservation to the available stock bucket. */
    @Update("update sku_stock "
            + "set stock = stock + #{count}, lock_stock = lock_stock - #{count} "
            + "where sku_id = #{skuId} and lock_stock >= #{count}")
    int release(@Param("skuId") Long skuId, @Param("count") Integer count);

    /** Consumes a reservation after payment has been confirmed. */
    @Update("update sku_stock set lock_stock = lock_stock - #{count} "
            + "where sku_id = #{skuId} and lock_stock >= #{count}")
    int confirm(@Param("skuId") Long skuId, @Param("count") Integer count);
}
