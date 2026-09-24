package com.why.fulfillment.commerce.checkout.dto;

import java.math.BigDecimal;

/**
 * 结算结果。
 *
 * @param orderId     订单号；结果未知时可能为空
 * @param state       与订单服务一致的状态字：RESERVED / FAILED / PENDING_COMPENSATION / COMPENSATED
 * @param message     给人看的说明
 * @param totalAmount 服务端重新计算后的实际下单金额
 * @param replayed    true 表示这次请求命中了同一个幂等键的既有结果，不是新下的单
 */
public record CheckoutResultView(Long orderId, String state, String message,
                                 BigDecimal totalAmount, boolean replayed) {
}
