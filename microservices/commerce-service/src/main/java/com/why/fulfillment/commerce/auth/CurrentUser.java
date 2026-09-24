package com.why.fulfillment.commerce.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标在 {@link JwtPrincipal} 参数上，表示该接口必须携带有效令牌。
 *
 * <p>身份只从 Authorization 头解析，绝不从 X-User-Id 这类请求头读——
 * 请求头是调用方可控的，直接信它等于放开越权。</p>
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
