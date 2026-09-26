package com.why.fulfillment.commerce.checkout.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;

/**
 * 结算结果。
 *
 * @param orderId     订单号；JSON 使用字符串避免浏览器丢失长整数精度，结果未知时可能为空
 * @param state       RESERVED 表示原结算已接受（不代表订单仍待支付）；其他终态保留 FAILED / COMPENSATED / CANCELED / CLOSED
 * @param message     给人看的说明
 * @param totalAmount 服务端重新计算后的实际下单金额
 * @param replayed    true 表示这次请求命中了同一个幂等键的既有结果，不是新下的单
 * @param cartCleanupRequired true 表示购物车可能保留原商品；恢复不自动清车，同步清理只有行版本完全匹配才清除
 */
public record CheckoutResultView(@JsonSerialize(using = ToStringSerializer.class) Long orderId,
                                 String state, String message,
                                 BigDecimal totalAmount, boolean replayed, boolean cartCleanupRequired) {
    public CheckoutResultView(Long orderId, String state, String message,
                              BigDecimal totalAmount, boolean replayed) {
        this(orderId, state, message, totalAmount, replayed, false);
    }
}
