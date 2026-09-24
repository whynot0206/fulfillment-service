package com.why.fulfillment.commerce.product.mapper;

import com.why.fulfillment.commerce.product.entity.ProductImage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface ProductImageMapper {

    @Select("""
            select id, spu_id, url, sort_order
            from product_image where spu_id = #{spuId}
            order by sort_order, id
            """)
    List<ProductImage> selectBySpuId(@Param("spuId") Long spuId);

    /**
     * 列表页批量取图。
     *
     * <p>一次 IN 查出所有图，由 Service 在内存里按 sort_order 取首图，
     * 而不是每个 SPU 查一次——列表页 N+1 是最容易写出来的慢查询。</p>
     */
    @Select("""
            <script>
            select id, spu_id, url, sort_order
            from product_image
            where spu_id in
            <foreach item="id" collection="spuIds" open="(" separator="," close=")">#{id}</foreach>
            order by spu_id, sort_order, id
            </script>
            """)
    List<ProductImage> selectBySpuIds(@Param("spuIds") Collection<Long> spuIds);
}
