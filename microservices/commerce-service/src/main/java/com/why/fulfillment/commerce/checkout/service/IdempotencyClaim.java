package com.why.fulfillment.commerce.checkout.service;

import com.why.fulfillment.commerce.checkout.entity.CheckoutRequest;

/**
 * 认领幂等键的结果。
 *
 * <p>三种，缺一不可：</p>
 * <ul>
 *   <li>{@link Outcome#CLAIMED} —— 这个键是新的，继续下单，{@code claimId} 用于稍后回填订单号。</li>
 *   <li>{@link Outcome#REPLAY} —— 同键同载荷，已经有结果了，直接返回 {@code existing}，
 *       不要再下一次单。这是重试的正常答案，不是错误。</li>
 *   <li>{@link Outcome#CONFLICT} —— 同键不同载荷。前端复用了幂等键但购物车已经变了，
 *       两种意图撞在同一个键上，只能拒绝。悄悄按新载荷下单会让用户买到他没确认的东西。</li>
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
