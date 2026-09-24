package com.why.fulfillment.commerce.product.mapper;

import com.why.fulfillment.commerce.product.entity.ProductSpu;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * SPU 查询。
 *
 * <p>只读接口：商品维护走后台，MVP 不开放写入。所有列表查询都强制
 * {@code status = 1}，下架商品不应出现在任何面向买家的列表里。</p>
 */
@Mapper
public interface ProductSpuMapper {

    /**
     * 分页查询上架 SPU。
     *
     * <p>排序键是 {@code id desc} 而不是 {@code create_time desc}：
     * create_time 上没有索引，而 idx_spu_status_id 正好是 (status, id)，
     * 按 id 排能直接走索引顺序，不需要 filesort。</p>
     */
    @Select("""
            <script>
            select id, name, description, brand, category_id, status, create_time, update_time
            from product_spu
            where status = 1
            <if test="categoryId != null">
              and category_id = #{categoryId}
            </if>
            <if test="keyword != null and keyword != ''">
              and name like concat('%', #{keyword}, '%')
            </if>
            order by id desc
            limit #{limit} offset #{offset}
            </script>
            """)
    List<ProductSpu> selectListedPage(@Param("categoryId") Long categoryId,
                                      @Param("keyword") String keyword,
                                      @Param("offset") int offset,
                                      @Param("limit") int limit);

    @Select("""
            <script>
            select count(*) from product_spu
            where status = 1
            <if test="categoryId != null">
              and category_id = #{categoryId}
            </if>
            <if test="keyword != null and keyword != ''">
              and name like concat('%', #{keyword}, '%')
            </if>
            </script>
            """)
    long countListed(@Param("categoryId") Long categoryId,
                     @Param("keyword") String keyword);

    /** 按主键查，不过滤状态——调用方需要区分「不存在」和「已下架」。 */
    @Select("""
            select id, name, description, brand, category_id, status, create_time, update_time
            from product_spu where id = #{spuId}
            """)
    ProductSpu selectById(@Param("spuId") Long spuId);
}
