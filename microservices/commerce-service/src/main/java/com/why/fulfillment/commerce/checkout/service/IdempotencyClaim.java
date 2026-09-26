package com.why.fulfillment.commerce.checkout.service;

import com.why.fulfillment.commerce.checkout.entity.CheckoutRequest;

/**
 * 认领幂等键的结果。
 *
 * <p>三种，缺一不可：</p>
 * <ul>
 *   <li>{@link Outcome#CLAIMED} —— 新键的订单号和完整快照已一起写入，继续远程下单。</li>
 *   <li>{@link Outcome#REPLAY} —— 键已绑定原意图，金额确认一致时返回 {@code existing}，
 *       不要再下一次单。这是重试的正常答案，不是错误。</li>
 *   <li>{@link Outcome#CONFLICT} —— 同键金额不匹配或历史信息不完整，禁止按新购物车下单。
 *       同键始终指向原意图，即使购物车已清空、改价或重新加购；新意图须使用新键。</li>
 * </ul>
 */
public record IdempotencyClaim(Outcome outcome, Long claimId, CheckoutRequest existing) {

    public enum Outcome {
        CLAIMED,
        REPLAY,
        CONFLICT
    }

    public static IdempotencyClaim claimed(long claimId) {
        return new IdempotencyClaim(Outcome.CLAIMED, claimId, null);
    }

    public static IdempotencyClaim replay(CheckoutRequest existing) {
        return new IdempotencyClaim(Outcome.REPLAY, null, existing);
    }

    public static IdempotencyClaim conflict(CheckoutRequest existing) {
        return new IdempotencyClaim(Outcome.CONFLICT, null, existing);
    }
}
