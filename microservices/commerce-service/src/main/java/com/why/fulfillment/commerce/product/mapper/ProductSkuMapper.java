package com.why.fulfillment.commerce.product.mapper;

import com.why.fulfillment.commerce.product.entity.ProductSku;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * SKU 查询。
 *
 * <p>注意 {@link #selectByIds} 不过滤 status：购物车和结算必须能读到已下架
 * SKU，才能告诉用户「这件下架了」。如果这里顺手加上 status = 1，
 * 下架商品会从购物车里凭空消失，用户看到的是总价对不上。</p>
 */
@Mapper
public interface ProductSkuMapper {

    @Select("""
            select id, spu_id, sku_code, spec_json, price, status, create_time, update_time
            from product_sku where id = #{skuId}
            """)
    ProductSku selectById(@Param("skuId") Long skuId);

    @Select("""
            <script>
            select id, spu_id, sku_code, spec_json, price, status, create_time, update_time
            from product_sku
            where id in
            <foreach item="id" collection="skuIds" open="(" separator="," close=")">#{id}</foreach>
            order by id
            </script>
            """)
    List<ProductSku> selectByIds(@Param("skuIds") Collection<Long> skuIds);

    /** 详情页用：只返回上架 SKU。 */
    @Select("""
            select id, spu_id, sku_code, spec_json, price, status, create_time, update_time
            from product_sku
            where spu_id = #{spuId} and status = 1
            order by id
            """)
    List<ProductSku> selectListedBySpuId(@Param("spuId") Long spuId);

    /** 列表页用：取每个 SPU 的最低上架价作为「起售价」。 */
    @Select("""
            <script>
            select spu_id as spuId, min(price) as price
            from product_sku
            where status = 1 and spu_id in
            <foreach item="id" collection="spuIds" open="(" separator="," close=")">#{id}</foreach>
            group by spu_id
            </script>
            """)
    List<SpuMinPrice> selectMinPriceBySpuIds(@Param("spuIds") Collection<Long> spuIds);

    /** {@link #selectMinPriceBySpuIds} 的投影结果。 */
    record SpuMinPrice(Long spuId, java.math.BigDecimal price) {
    }
}
