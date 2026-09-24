package com.why.fulfillment.commerce.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtCodecTest {

    /** 与 gateway 模块 AuthenticationGlobalFilterTest.TEST_SECRET 同值。 */
    private static final String TEST_SECRET = "test-only-secret-value-at-least-32-chars";

    private static final String ISSUER = "fulfillment-commerce";

    /**
     * 与 gateway 模块 AuthenticationGlobalFilterTest.SHARED_TEST_TOKEN 逐字符一致。
     *
     * <p>两个模块各有一份 HMAC 实现，这个向量是唯一能发现它们分叉的东西。
     * 任何一边改了令牌格式，另一边的同名测试必须同时改——如果只改了一边，
     * 红的那个测试就是提醒。</p>
     */
    private static final String SHARED_TEST_TOKEN =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
                    + ".eyJpc3MiOiJmdWxmaWxsbWVudC1jb21tZXJjZSIsInN1YiI6IjciLCJuYW1lIjoidGVzdGVyIiwiaWF0IjoxNzU4NTg1NjAwLCJleHAiOjQxMDI0NDQ4MDB9"
                    + ".0TK-f-oKJNq0F-08g8h9vxcrp5Tt3xXOWqHl_0qAXmY";

    private final JwtCodec codec = new JwtCodec(TEST_SECRET, ISSUER, 7200);

    @Test
    void verifiesTheSharedTestVector() {
        JwtPrincipal principal = codec.verify(SHARED_TEST_TOKEN);

        assertThat(principal.userId()).isEqualTo(7L);
        assertThat(principal.username()).isEqualTo("tester");
    }

    @Test
    void roundTripsIssuedToken() {
        String token = codec.issue(42L, "why");

        JwtPrincipal principal = codec.verify(token);

        assertThat(principal.userId()).isEqualTo(42L);
        assertThat(principal.username()).isEqualTo("why");
    }

    /** 改一个字符就应该验不过——这条测的是「确实在验签」，不是「解析成功了」。 */
    @Test
    void rejectsTamperedPayload() {
        String token = codec.issue(42L, "why");
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 1) + "X." + parts[2];

        assertThatThrownBy(() -> codec.verify(tampered))
                .isInstanceOf(JwtVerificationException.class);
    }

    /**
     * 过期判定。
     *
     * <p>这串令牌的签名是**对的**——用同一个密钥签的，exp=1000003600（2001 年）。
     * 签名故意留对，是为了让这条测试只可能因为「过期」而失败；
     * 如果随手写一个签名错的串，它会在验签那步就被拒，
     * 测试是绿的，但 exp 分支从来没被跑到过。</p>
     */
    @Test
    void rejectsExpiredTokenWithValidSignature() {
        String expired = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
                + ".eyJpc3MiOiJmdWxmaWxsbWVudC1jb21tZXJjZSIsInN1YiI6IjciLCJuYW1lIjoidGVzdGVyIiwiaWF0IjoxMDAwMDAwMDAwLCJleHAiOjEwMDAwMDM2MDB9"
                + ".cMFF9_SgrTYQycHANZg-WBhnRS8Hhk99om-THByKdnw";

        assertThatThrownBy(() -> codec.verify(expired))
                .isInstanceOf(JwtVerificationException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void rejectsShortSecretAtConstruction() {
        assertThatThrownBy(() -> new JwtCodec("too-short", ISSUER, 7200))
                .isInstanceOf(IllegalStateException.class);
    }
}
