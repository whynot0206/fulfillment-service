package com.why.fulfillment.commerce.auth;

import com.why.fulfillment.commerce.common.CommerceException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 把 Authorization 头解析成 {@link JwtPrincipal} 注入 Controller 参数。
 *
 * <p>Commerce 自己验签，而不是信任 Gateway 注入的 X-User-Id。理由：
 * Gateway 的注入是给不懂 JWT 的下游服务（order-service）用的便利；
 * Commerce 是签发方，直接暴露在 18084 端口上时也必须自己站得住。
 * 只要有一条路径能绕开 Gateway 打到 18084，信任请求头就等于没有鉴权。</p>
 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtCodec jwtCodec;

    public CurrentUserArgumentResolver(JwtCodec jwtCodec) {
        this.jwtCodec = jwtCodec;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && JwtPrincipal.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer container,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        String header = request == null ? null : request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw CommerceException.unauthorized("TOKEN_MISSING", "请先登录");
        }
        try {
            return jwtCodec.verify(header.substring(BEARER_PREFIX.length()).trim());
        } catch (JwtVerificationException exception) {
            // 不把具体原因（过期 / 签名不对 / 格式错）透给调用方。
            throw CommerceException.unauthorized("TOKEN_INVALID", "登录状态已失效，请重新登录");
        }
    }
}
