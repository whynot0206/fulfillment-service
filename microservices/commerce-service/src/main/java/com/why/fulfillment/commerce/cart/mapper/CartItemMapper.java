package com.why.fulfillment.commerce.cart.mapper;

import com.why.fulfillment.commerce.cart.entity.CartItem;
import com.why.fulfillment.commerce.cart.service.CartItemSnapshot;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 购物车读写。
 *
 * <p><b>每一条写语句都带 user_id 条件</b>，即使已经有 id 也一样。
 * 靠「先查出来确认归属、再按 id 改」是两步操作，中间存在窗口；
 * 把归属条件写进 WHERE 里，越权就变成影响行数 0，一步到位。</p>
 */
@Mapper
public interface CartItemMapper {

    @Select("""
            select id, user_id, sku_id, quantity, selected, revision, create_time, update_time
            from cart_item where user_id = #{userId}
            order by update_time desc, id desc
            """)
    List<CartItem> selectByUserId(@Param("userId") Long userId);

    @Select("select count(*) from cart_item where user_id = #{userId}")
    int countByUserId(@Param("userId") Long userId);

    @Select("""
            select id, user_id, sku_id, quantity, selected, revision, create_time, update_time
            from cart_item where user_id = #{userId} and sku_id = #{skuId}
            """)
    CartItem selectByUserAndSku(@Param("userId") Long userId, @Param("skuId") Long skuId);

    /**
     * 加购：存在则累加，不存在则插入。
     *
     * <p>用 ON DUPLICATE KEY UPDATE 而不是「查一下在不在，在就 update 否则 insert」：
     * 后者在同一用户双击加购时，两个请求都会查到「不存在」，然后一个 insert 成功、
     * 一个撞唯一键报错——用户看到的是加购失败。这里一条语句完成，
     * 冲突由 uk_cart_user_sku 在存储引擎内部串行化。</p>
     *
     * <p>上限校验放在 SQL 里，是因为它依赖 quantity 的**当前值**。
     * 在 Java 里先读再判会读到过期值，两个并发请求可以把数量叠到上限之上。</p>
     */
    @Insert("""
            insert into cart_item (user_id, sku_id, quantity, selected)
            values (#{userId}, #{skuId}, #{quantity}, 1)
            on duplicate key update
              quantity = least(quantity + #{quantity}, #{maxQuantity}),
              selected = 1,
              revision = revision + 1
            """)
    int upsertAccumulate(@Param("userId") Long userId,
                         @Param("skuId") Long skuId,
                         @Param("quantity") Integer quantity,
                         @Param("maxQuantity") Integer maxQuantity);

    @Update("""
            update cart_item set quantity = #{quantity}, revision = revision + 1
            where user_id = #{userId} and sku_id = #{skuId}
            """)
    int updateQuantity(@Param("userId") Long userId,
                       @Param("skuId") Long skuId,
                       @Param("quantity") Integer quantity);

    @Update("""
            update cart_item set selected = #{selected}, revision = revision + 1
            where user_id = #{userId} and sku_id = #{skuId}
            """)
    int updateSelected(@Param("userId") Long userId,
                       @Param("skuId") Long skuId,
                       @Param("selected") boolean selected);

    @Delete("delete from cart_item where user_id = #{userId} and sku_id = #{skuId}")
    int delete(@Param("userId") Long userId, @Param("skuId") Long skuId);

    /** Only original, unchanged rows may be removed; new same-SKU rows or edits survive checkout. */
    @Delete("""
            <script>
            delete from cart_item
            where user_id = #{userId} and
            <foreach item="snapshot" collection="snapshots" open="(" separator=" or " close=")">
              (id = #{snapshot.rowId} and sku_id = #{snapshot.skuId} and revision = #{snapshot.revision})
            </foreach>
            </script>
            """)
    int deleteUnchangedSnapshots(@Param("userId") Long userId,
                                 @Param("snapshots") List<CartItemSnapshot> snapshots);
}
