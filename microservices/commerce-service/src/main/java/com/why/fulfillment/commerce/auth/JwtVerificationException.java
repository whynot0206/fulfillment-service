package com.why.fulfillment.commerce.auth;

/** 令牌无效：签名不对、过期、格式错、签发方不匹配。对外一律回 401，不区分原因。 */
public class JwtVerificationException extends RuntimeException {

    public JwtVerificationException(String message) {
        super(message);
    }

    public JwtVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
