package com.why.fulfillment.commerce.auth.dto;

/**
 * 登录 / 注册成功后的返回体。
 *
 * @param token      Bearer 令牌
 * @param expiresIn  有效期（秒）
 * @param userId     用户编号
 * @param username   用户名
 */
public record AuthTokenView(String token, long expiresIn, long userId, String username) {
}
