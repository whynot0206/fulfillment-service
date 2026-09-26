package com.why.fulfillment.gateway.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * 网关鉴权：校验 JWT，向下游注入可信的用户标识。
 *
 * <p>普通消费者从 Commerce 结算，由服务端确定用户和价格。历史下单入口仍接受
 * 请求体中的用户和价格，只用于受控实验，默认在网关禁止，不能靠注入用户头保护它们。
 * 其他消费者请求统一做两件事：</p>
 * <ol>
 *   <li><b>先无条件剥离</b>客户端自带的 {@code X-User-Id}。放在校验之前、
 *       白名单判断之前——只要有一条路径忘了剥，整套注入机制就等于零。</li>
 *   <li>校验通过后注入本进程算出来的 {@code X-User-Id}。</li>
 * </ol>
 *
 * <p>Order 的 /api/orders 路由还要求内部令牌。网关剥离客户端伪造的令牌后，
 * 在该路由上重新设置令牌；直连 Order 并伪造 X-User-Id 会被拒绝。
 * 部署时仍应限制服务端口的网络访问范围。</p>
 */
@Component
public class AuthenticationGlobalFilter implements GlobalFilter, Ordered {

    /** 下游据此识别调用者。名字改动要同步改 order-service。 */
    public static final String USER_ID_HEADER = "X-User-Id";

    /** 一并剥离，避免下游误把它当身份来源。Gateway 不注入用户名。 */
    private static final List<String> STRIPPED_HEADERS = List.of(
            USER_ID_HEADER, "X-Username", "X-Internal-Service-Token");

    private static final String BEARER_PREFIX = "Bearer ";
    private static final PathPattern ORDER_API_PATH = new PathPatternParser().parse("/api/orders/**");
    private static final List<PathPattern> OWNED_ORDER_WRITE_PATHS = List.of(
            "/api/orders/{orderId:[0-9]+}/cancel", "/api/orders/{orderId:[0-9]+}/cancel/")
            .stream().map(new PathPatternParser()::parse).toList();

    private static final Logger log = LoggerFactory.getLogger(AuthenticationGlobalFilter.class);

    private final JwtTokenVerifier verifier;
    private final List<PathPattern> anonymousPatterns;
    private final boolean legacyOrderCreateEnabled;

    public AuthenticationGlobalFilter(
            JwtTokenVerifier verifier,
            @Value("${gateway.auth.anonymous-paths}") String[] anonymousPaths,
            @Value("${gateway.experimental.legacy-order-create-enabled:false}") boolean legacyOrderCreateEnabled) {
        this.verifier = verifier;
        this.legacyOrderCreateEnabled = legacyOrderCreateEnabled;
        PathPatternParser parser = new PathPatternParser();
        this.anonymousPatterns = Arrays.stream(anonymousPaths)
                .map(String::trim)
                .filter(pattern -> !pattern.isEmpty())
                .map(parser::parse)
                .toList();
        if (legacyOrderCreateEnabled) {
            log.warn("Legacy order creation is enabled for a protected local experiment only. "
                    + "JWT is still required, but request-body user and price are not consumer-authorized. "
                    + "Do not expose this gateway to ordinary consumers; the switch is not authorization.");
        }
    }

    @Override
    public int getOrder() {
        // 必须早于 RouteToRequestUrlFilter(10000) 与所有转发过滤器。
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // 第一步永远是剥离，与后面的分支无关。
        ServerHttpRequest.Builder mutated = request.mutate()
                .headers(headers -> STRIPPED_HEADERS.forEach(headers::remove));

        boolean legacyOrderCreation = isExperimentalOrderWrite(exchange);
        if (legacyOrderCreation && !legacyOrderCreateEnabled) {
            return reject(exchange, HttpStatus.FORBIDDEN, "LEGACY_ORDER_ENTRY_DISABLED",
                    "实验下单入口未开放，请通过购物车结算");
        }

        // An experiment opt-in must never turn these unsafe legacy writes into anonymous APIs,
        // even if someone broadens the anonymous-paths configuration.
        if (!legacyOrderCreation && isAnonymous(exchange)) {
            return chain.filter(withRequest(exchange, mutated));
        }

        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return reject(exchange, "TOKEN_MISSING", "请先登录");
        }

        long userId;
        try {
            userId = verifier.verifyAndExtractUserId(authorization.substring(BEARER_PREFIX.length()).trim());
        } catch (JwtTokenVerifier.InvalidTokenException exception) {
            log.debug("Rejected request to {}: {}", request.getPath().value(), exception.getMessage());
            return reject(exchange, "TOKEN_INVALID", "登录状态已失效，请重新登录");
        }

        mutated.header(USER_ID_HEADER, Long.toString(userId));
        return chain.filter(withRequest(exchange, mutated));
    }

    private boolean isAnonymous(ServerWebExchange exchange) {
        var path = exchange.getRequest().getPath().pathWithinApplication();
        return anonymousPatterns.stream().anyMatch(pattern -> pattern.matches(path));
    }

    private static boolean isExperimentalOrderWrite(ServerWebExchange exchange) {
        if (exchange.getRequest().getMethod() != HttpMethod.POST) {
            return false;
        }
        var path = exchange.getRequest().getPath().pathWithinApplication();
        // Deny unrecognized POSTs in this route as well: a downstream servlet container may
        // normalize dot segments into a legacy creation endpoint after the gateway check.
        return ORDER_API_PATH.matches(path)
                && OWNED_ORDER_WRITE_PATHS.stream().noneMatch(pattern -> pattern.matches(path));
    }

    private static ServerWebExchange withRequest(ServerWebExchange exchange, ServerHttpRequest.Builder builder) {
        return exchange.mutate().request(builder.build()).build();
    }

    private static Mono<Void> reject(ServerWebExchange exchange, String code, String message) {
        return reject(exchange, HttpStatus.UNAUTHORIZED, code, message);
    }

    private static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status,
                                     String code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
