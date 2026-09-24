package com.why.fulfillment.commerce.checkout.mapper;

import com.why.fulfillment.commerce.checkout.entity.CheckoutRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 结算幂等记录。
 *
 * <p><b>这里没有「先查有没有、没有再插」的方法，是故意的。</b>查和插之间有窗口，
 * 同一个用户双击提交的两个请求可以同时查到「没有」，然后都去插。
 * 唯一键 uk_checkout_user_key 是唯一的裁判：让两个请求都去 insert，
 * 输的那个拿到 DuplicateKeyException，再按已有记录决定是重放还是冲突。</p>
 */
@Mapper
public interface CheckoutRequestMapper {

    /**
     * 认领幂等键。唯一键冲突时抛 DuplicateKeyException，由调用方处理。
     *
     * <p>插入时 order_id 为 NULL、status 为「处理中」：订单号是下一步才产生的，
     * 先占坑再回填。此时若进程崩溃，留下的就是一条 status=0 的记录，
     * 它的存在本身就是「这次结算结果未知」的证据。</p>
     */
    @Insert("""
            insert into checkout_request (user_id, idempotency_key, request_digest, total_amount, order_id, status)
            values (#{userId}, #{idempotencyKey}, #{requestDigest}, #{totalAmount}, null, 0)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertClaim(CheckoutRequest claim);

    @Select("""
            select id, user_id, idempotency_key, request_digest, total_amount, order_id, status,
                   last_error, create_time, update_time
              from checkout_request
             where user_id = #{userId} and idempotency_key = #{idempotencyKey}
            """)
    CheckoutRequest selectByUserAndKey(@Param("userId") long userId,
                                       @Param("idempotencyKey") String idempotencyKey);

    /**
     * 回填订单号并标记已提交。
     *
     * <p>WHERE 带 status = 0：只有还在「处理中」的记录才能被推进。
     * 不加这个条件的话，一条已经拒绝的记录会被迟到的成功响应改成已提交。</p>
     *
     * @return 影响行数；0 表示这条记录已经被别的线程推进过了
     */
    @Update("""
            update checkout_request
               set order_id = #{orderId}, status = 1, last_error = null
             where id = #{id} and status = 0
            """)
    int markSubmitted(@Param("id") long id, @Param("orderId") long orderId);

    /**
     * 同上，标记拒绝。
     *
     * <p>{@code orderId} 可以为 null：订单号还没发出来就被拒的情况（比如价格已变），
     * 确实没有订单可关联。MySQL 的唯一索引允许多行 NULL，所以这不会撞
     * uk_checkout_order。已经发出订单号再被下游拒绝的情况则要把号记下来，
     * 否则那个订单在 Commerce 这边就成了无主记录，排查时找不到来源。</p>
     *
     * <p>错误原因截断到 500 由调用方负责。</p>
     */
    @Update("""
            update checkout_request
               set status = 2, order_id = #{orderId}, last_error = #{error}
             where id = #{id} and status = 0
            """)
    int markRejected(@Param("id") long id, @Param("orderId") Long orderId,
                     @Param("error") String error);
}
