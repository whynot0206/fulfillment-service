package com.why.fulfillment.commerce.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;

/**
 * 最小 JWT 实现（HS256），负责签发与校验。
 *
 * <p><b>令牌格式（Gateway 侧必须与此一致）：</b>
 * <pre>
 * header  = {"alg":"HS256","typ":"JWT"}
 * payload = {"iss":&lt;签发方&gt;,"sub":"&lt;userId&gt;","name":"&lt;username&gt;",
 *            "iat":&lt;秒&gt;,"exp":&lt;秒&gt;}
 * token   = b64url(header) + "." + b64url(payload) + "." + b64url(HMAC-SHA256(前两段))
 * </pre>
 * base64url 一律无填充（{@code =} 去掉）。
 *
 * <p><b>为什么不用 jjwt/Nimbus：</b>一是这套项目的取舍标准是「面试会追问的机制
 * 自己写」，JWT 的签名与校验正好是会被追问的那一类；二是 payment 模块已经在用
 * JDK 的 HMAC-SHA256 做回调验签，口径统一。代价是功能面窄——没有 JWK、没有
 * 密钥轮换、没有 RS256。真要多方验签必须换成成熟库，别在这个类上加。</p>
 *
 * <p><b>与 Gateway 的重复：</b>gateway 模块有一份只做校验的
 * {@code JwtTokenVerifier}，逻辑必须与本类保持一致。没有抽到 fulfillment-api 是
 * 因为那个模块依赖 spring-boot-starter-web，会把 Tomcat 拖进 Gateway 的
 * WebFlux 进程。改本类的令牌格式时必须同步改 Gateway 那份。</p>
 */
@Component
public class JwtCodec {

    /** 128 位以下的密钥对 HMAC-SHA256 来说太短，直接拒绝启动。 */
    private static final int MIN_SECRET_LENGTH = 32;

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final byte[] secret;
    private final String issuer;
    private final long ttlSeconds;

    public JwtCodec(@Value("${auth.jwt.secret}") String secret,
                    @Value("${auth.jwt.issuer}") String issuer,
                    @Value("${auth.jwt.ttl-seconds}") long ttlSeconds) {
        if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "AUTH_JWT_SECRET must be configured with at least " + MIN_SECRET_LENGTH + " characters");
        }
        if (ttlSeconds <= 0) {
            throw new IllegalStateException("auth.jwt.ttl-seconds must be positive");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.issuer = issuer;
        this.ttlSeconds = ttlSeconds;
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    public String issue(long userId, String username) {
        long now = Instant.now().getEpochSecond();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("iss", issuer);
        payload.put("sub", Long.toString(userId));
        payload.put("name", username);
        payload.put("iat", now);
        payload.put("exp", now + ttlSeconds);

        String encodedHeader = ENCODER.encodeToString(HEADER_JSON.getBytes(StandardCharsets.UTF_8));
        String encodedPayload;
        try {
            encodedPayload = ENCODER.encodeToString(objectMapper.writeValueAsBytes(payload));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialise JWT payload", exception);
        }
        String signingInput = encodedHeader + "." + encodedPayload;
        return signingInput + "." + ENCODER.encodeToString(sign(signingInput));
    }

    /**
     * 校验并解析。
     *
     * <p>顺序很重要：先验签，再读内容。反过来写就等于在信任未验证的数据。</p>
     */
    public JwtPrincipal verify(String token) {
        if (token == null || token.isBlank()) {
            throw new JwtVerificationException("empty token");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new JwtVerificationException("malformed token");
        }

        byte[] expected = sign(parts[0] + "." + parts[1]);
        byte[] actual;
        try {
            actual = DECODER.decode(parts[2]);
        } catch (IllegalArgumentException exception) {
            throw new JwtVerificationException("malformed signature", exception);
        }
        // 定长比较，避免按字节短路带来的时序信息泄露。
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new JwtVerificationException("signature mismatch");
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(DECODER.decode(parts[1]));
        } catch (Exception exception) {
            throw new JwtVerificationException("malformed payload", exception);
        }

        if (!issuer.equals(payload.path("iss").asText(null))) {
            throw new JwtVerificationException("issuer mismatch");
        }
        long exp = payload.path("exp").asLong(0L);
        if (exp <= Instant.now().getEpochSecond()) {
            throw new JwtVerificationException("token expired");
        }

        String subject = payload.path("sub").asText(null);
        if (subject == null) {
            throw new JwtVerificationException("missing subject");
        }
        try {
            return new JwtPrincipal(Long.parseLong(subject), payload.path("name").asText(""));
        } catch (NumberFormatException exception) {
            throw new JwtVerificationException("non-numeric subject", exception);
        }
    }

    private byte[] sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", exception);
        }
    }
}
