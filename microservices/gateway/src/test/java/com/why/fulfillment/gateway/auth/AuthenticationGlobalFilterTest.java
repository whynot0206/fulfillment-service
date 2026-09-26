package com.why.fulfillment.gateway.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 网关鉴权的行为约束。
 *
 * <p>这里的每条断言都对应一个具体的越权手法，不是为了覆盖率凑的。</p>
 */
class AuthenticationGlobalFilterTest {

    /**
     * 跨模块测试向量：同一串令牌在 commerce-service 的 JwtCodecTest 里也要能验过。
     *
     * <p>两边各写了一份 HMAC 实现，这个常量是防止它们悄悄分叉的唯一保险。
     * 改令牌格式时这个向量会先红。</p>
     *
     * <p>内容：iss=fulfillment-commerce, sub=7, name=tester, exp=4102444800（2100 年）。</p>
     */
    static final String SHARED_TEST_TOKEN =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
                    + ".eyJpc3MiOiJmdWxmaWxsbWVudC1jb21tZXJjZSIsInN1YiI6IjciLCJuYW1lIjoidGVzdGVyIiwiaWF0IjoxNzU4NTg1NjAwLCJleHAiOjQxMDI0NDQ4MDB9"
                    + ".0TK-f-oKJNq0F-08g8h9vxcrp5Tt3xXOWqHl_0qAXmY";

    /** 测试专用，不是任何环境的真实密钥。 */
    static final String TEST_SECRET = "test-only-secret-value-at-least-32-chars";

    private static final String[] ANONYMOUS_PATHS = {"/api/auth/**", "/api/products/**"};

    private final JwtTokenVerifier verifier = new JwtTokenVerifier(TEST_SECRET, "fulfillment-commerce");
    private final AuthenticationGlobalFilter filter = new AuthenticationGlobalFilter(verifier, ANONYMOUS_PATHS, false);

    @Test
    void verifiesTheSharedTestVector() {
        assertThat(verifier.verifyAndExtractUserId(SHARED_TEST_TOKEN)).isEqualTo(7L);
    }

    @Test
    void rejectsTokenSignedWithAnotherSecret() {
        JwtTokenVerifier other = new JwtTokenVerifier("another-secret-value-at-least-32-characters", "fulfillment-commerce");

        assertThatThrownBy(() -> other.verifyAndExtractUserId(SHARED_TEST_TOKEN))
                .isInstanceOf(JwtTokenVerifier.InvalidTokenException.class);
    }

    @Test
    void rejectsTokenFromAnotherIssuer() {
        JwtTokenVerifier other = new JwtTokenVerifier(TEST_SECRET, "someone-else");

        assertThatThrownBy(() -> other.verifyAndExtractUserId(SHARED_TEST_TOKEN))
                .isInstanceOf(JwtTokenVerifier.InvalidTokenException.class);
    }

    /** 最关键的一条：客户端自带的 X-User-Id 必须被覆盖，不能透到下游。 */
    @Test
    void overwritesClientSuppliedUserIdHeader() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/orders")
                        .header(AuthenticationGlobalFilter.USER_ID_HEADER, "999")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + SHARED_TEST_TOKEN));

        ServerWebExchange forwarded = capture(exchange);

        assertThat(forwarded.getRequest().getHeaders()
                .getFirst(AuthenticationGlobalFilter.USER_ID_HEADER)).isEqualTo("7");
    }

    @Test
    void stripsClientSuppliedInternalTokenBeforePaymentRouting() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/payments/orders/12/mock-success")
                        .header("X-Internal-Service-Token", "forged")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + SHARED_TEST_TOKEN));

        ServerWebExchange forwarded = capture(exchange);

        assertThat(forwarded.getRequest().getHeaders().containsKey("X-Internal-Service-Token")).isFalse();
        assertThat(forwarded.getRequest().getHeaders().getFirst("X-User-Id")).isEqualTo("7");
    }

    /** 白名单路径也要剥离——否则匿名接口就成了伪造请求头的入口。 */
    @Test
    void stripsClientSuppliedUserIdOnAnonymousPaths() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/products/1")
                        .header(AuthenticationGlobalFilter.USER_ID_HEADER, "999"));

        ServerWebExchange forwarded = capture(exchange);

        assertThat(forwarded.getRequest().getHeaders()
                .containsKey(AuthenticationGlobalFilter.USER_ID_HEADER)).isFalse();
    }

    @Test
    void rejectsProtectedPathWithoutToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/cart"));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange, recording(forwarded)).block();

        assertThat(forwarded.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsProtectedPathWithGarbageToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/cart")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange, recording(forwarded)).block();

        assertThat(forwarded.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/orders", "/api/orders/", "/api/orders/redis", "/api/orders/redis/",
            "/api/orders;test=1", "/api/orders/redis;test=1", "/api/orders/ignored/..",
            "/api/orders/ignored/../redis", "/api/orders/redis/."})
    void rejectsLegacyCreationByDefaultEvenForAuthenticatedConsumers(String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + SHARED_TEST_TOKEN)
                        .header("X-User-Id", "999")
                        .header("X-Internal-Service-Token", "forged")
                        .body("{\"userId\":999,\"totalAmount\":0.01}"));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange, recording(forwarded)).block();

        assertThat(forwarded.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("LEGACY_ORDER_ENTRY_DISABLED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/orders", "/api/orders/12"})
    void legacyCreationGateDoesNotBlockOwnedOrderReads(String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + SHARED_TEST_TOKEN));

        assertThat(capture(exchange).getRequest().getHeaders().getFirst("X-User-Id")).isEqualTo("7");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/checkout", "/api/orders/12/cancel", "/api/payments/orders/12/mock-success"})
    void legacyCreationGateDoesNotBlockCheckoutOrOwnedOrderActions(String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + SHARED_TEST_TOKEN));

        assertThat(capture(exchange).getRequest().getHeaders().getFirst("X-User-Id")).isEqualTo("7");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/orders", "/api/orders/redis"})
    void experimentOptInStillRequiresAuthenticationEvenWithAnonymousWildcard(String path) {
        AuthenticationGlobalFilter experimentalFilter =
                new AuthenticationGlobalFilter(verifier, new String[]{"/api/**"}, true);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post(path));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        experimentalFilter.filter(exchange, recording(forwarded)).block();

        assertThat(forwarded.get()).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/orders", "/api/orders/redis"})
    void explicitExperimentOptInKeepsTrustedHeaderHandling(String path) {
        AuthenticationGlobalFilter experimentalFilter =
                new AuthenticationGlobalFilter(verifier, ANONYMOUS_PATHS, true);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + SHARED_TEST_TOKEN)
                        .header("X-User-Id", "999")
                        .header("X-Internal-Service-Token", "forged"));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        experimentalFilter.filter(exchange, recording(forwarded)).block();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getRequest().getHeaders().getFirst("X-User-Id")).isEqualTo("7");
        assertThat(forwarded.get().getRequest().getHeaders().containsKey("X-Internal-Service-Token")).isFalse();
    }

    private ServerWebExchange capture(MockServerWebExchange exchange) {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        filter.filter(exchange, recording(forwarded)).block();
        ServerWebExchange result = forwarded.get();
        assertThat(result).as("请求应当被放行").isNotNull();
        return result;
    }

    private static GatewayFilterChain recording(AtomicReference<ServerWebExchange> sink) {
        return exchange -> {
            sink.set(exchange);
            return Mono.empty();
        };
    }
}
