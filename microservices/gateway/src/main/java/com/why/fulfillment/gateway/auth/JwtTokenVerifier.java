package com.why.fulfillment.gateway.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 令牌校验（只校验，不签发）。
 *
 * <p><b>这是 commerce-service 里 {@code JwtCodec} 的对侧实现，两处必须保持一致。</b>
 * 为什么不抽成公共模块：fulfillment-api 依赖 spring-boot-starter-web，
 * 而 Gateway 是 WebFlux 进程，引进来会把 Tomcat 一起拖进来
 * （见 gateway/pom.xml 里那条注释）。为两个类新开一个模块又违背
 * 「两个服务用的就重复一份」的约定，所以这里选择重复 + 交叉注释。
 * 改令牌格式时必须同时改两边。</p>
 *
 * <p>Gateway 不查库，只验签名和过期时间。「用户是否被禁用」由
 * commerce-service 在业务接口里回查——Gateway 引入数据库依赖会把
 * 一个无状态转发层变成有状态的，不划算。</p>
 */
@Component
public class JwtTokenVerifier {

    private static final int MIN_SECRET_LENGTH = 32;
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final byte[] secret;
    private final String issuer;

    public JwtTokenVerifier(@Value("${auth.jwt.secret}") String secret,
                            @Value("${auth.jwt.issuer}") String issuer) {
        if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "AUTH_JWT_SECRET must be configured with at least " + MIN_SECRET_LENGTH + " characters");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.issuer = issuer;
    }

    /**
     * @return 令牌里的用户编号
     * @throws InvalidTokenException 签名不匹配、过期、格式错、签发方不符
     */
    public long verifyAndExtractUserId(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException("empty token");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new InvalidTokenException("malformed token");
        }

        byte[] expected = sign(parts[0] + "." + parts[1]);
        byte[] actual;
        try {
            actual = DECODER.decode(parts[2]);
        } catch (IllegalArgumentException exception) {
            throw new InvalidTokenException("malformed signature");
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new InvalidTokenException("signature mismatch");
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(DECODER.decode(parts[1]));
        } catch (Exception exception) {
            throw new InvalidTokenException("malformed payload");
        }
        if (!issuer.equals(payload.path("iss").asText(null))) {
            throw new InvalidTokenException("issuer mismatch");
        }
        if (payload.path("exp").asLong(0L) <= Instant.now().getEpochSecond()) {
            throw new InvalidTokenException("token expired");
        }
        String subject = payload.path("sub").asText(null);
        if (subject == null) {
            throw new InvalidTokenException("missing subject");
        }
        try {
            return Long.parseLong(subject);
        } catch (NumberFormatException exception) {
            throw new InvalidTokenException("non-numeric subject");
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

    /** 令牌不可信。原因只进日志，不进响应体。 */
    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message) {
            super(message);
        }
    }
}
