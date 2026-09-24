package com.why.fulfillment.commerce.auth;

/**
 * 通过身份校验后的调用者。
 *
 * @param userId   用户编号
 * @param username 用户名，只用于展示
 */
public record JwtPrincipal(long userId, String username) {
}
