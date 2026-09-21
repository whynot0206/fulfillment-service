package com.why.fulfillment.inventory.mapper;

import com.why.fulfillment.inventory.entity.SkuStockLock;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SkuStockLockMapper {

    /**
     * The lock rows are returned in the same order used by reserve.  FOR
     * UPDATE makes release and confirm mutually exclusive with each other.
     */
    @Select("select id, order_id, sku_id, spu_id, `count`, status, create_time, update_time "
            + "from sku_stock_lock where order_id = #{orderId} "
            + "order by sku_id for update")
    List<SkuStockLock> selectByOrderIdForUpdate(@Param("orderId") Long orderId);

    @Insert("insert into sku_stock_lock "
            + "(order_id, sku_id, spu_id, `count`, status) "
            + "values (#{orderId}, #{skuId}, #{spuId}, #{count}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(SkuStockLock lock);

    @Update("update sku_stock_lock set status = 2 "
            + "where id = #{id} and status = 1")
    int markReleasedIfLocked(@Param("id") Long id);

    @Update("update sku_stock_lock set status = 3 "
            + "where id = #{id} and status = 1")
    int markConfirmedIfLocked(@Param("id") Long id);
}
