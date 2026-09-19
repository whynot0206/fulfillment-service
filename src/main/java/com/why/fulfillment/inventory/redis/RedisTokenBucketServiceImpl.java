package com.why.fulfillment.inventory.redis;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Lua-backed token bucket.  Refill, admission, and state persistence happen
 * in one Redis script, so concurrent requests cannot both spend the same
 * token or calculate refill from stale state.
 */
@Service
public class RedisTokenBucketServiceImpl implements RedisTokenBucketService {

    public static final String BUCKET_KEY_PREFIX = "fulfillment:token-bucket:";

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> tokenBucketScript;

    public RedisTokenBucketServiceImpl(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.tokenBucketScript = new DefaultRedisScript<>();
        this.tokenBucketScript.setLocation(new ClassPathResource("lua/token_bucket.lua"));
        this.tokenBucketScript.setResultType(List.class);
    }

    @Override
    public RedisTokenBucketResult tryAcquire(String bucketName,
                                             long capacity,
                                             BigDecimal refillRatePerSecond,
                                             long requestedTokens) {
        if (bucketName == null || bucketName.isBlank()
                || capacity <= 0
                || refillRatePerSecond == null
                || refillRatePerSecond.signum() < 0
                || requestedTokens <= 0) {
            return result(false, RedisTokenBucketStatus.INVALID_REQUEST,
                    BigDecimal.ZERO, capacity, refillRatePerSecond, requestedTokens);
        }

        try {
            List<?> response = redisTemplate.execute(
                    tokenBucketScript,
                    List.of(bucketKey(bucketName)),
                    Long.toString(capacity),
                    refillRatePerSecond.stripTrailingZeros().toPlainString(),
                    Long.toString(requestedTokens),
                    Long.toString(System.currentTimeMillis()));
            if (response == null || response.size() < 2) {
                return result(false, RedisTokenBucketStatus.REDIS_ERROR,
                        BigDecimal.ZERO, capacity, refillRatePerSecond, requestedTokens);
            }

            long allowed = number(response.get(0));
            BigDecimal remaining = decimal(response.get(1));
            if (allowed == 1) {
                return result(true, RedisTokenBucketStatus.ALLOWED,
                        remaining, capacity, refillRatePerSecond, requestedTokens);
            }
            if (allowed == 0) {
                return result(false, RedisTokenBucketStatus.REJECTED,
                        remaining, capacity, refillRatePerSecond, requestedTokens);
            }
            return result(false, RedisTokenBucketStatus.INVALID_REQUEST,
                    remaining, capacity, refillRatePerSecond, requestedTokens);
        } catch (RuntimeException redisFailure) {
            return result(false, RedisTokenBucketStatus.REDIS_ERROR,
                    BigDecimal.ZERO, capacity, refillRatePerSecond, requestedTokens);
        }
    }

    @Override
    public String bucketKey(String bucketName) {
        if (bucketName == null || bucketName.isBlank()) {
            throw new IllegalArgumentException("bucketName is required");
        }
        return BUCKET_KEY_PREFIX + bucketName;
    }

    private static RedisTokenBucketResult result(boolean allowed,
                                                 RedisTokenBucketStatus status,
                                                 BigDecimal remaining,
                                                 long capacity,
                                                 BigDecimal refillRatePerSecond,
                                                 long requestedTokens) {
        return new RedisTokenBucketResult(allowed, status, remaining,
                capacity, refillRatePerSecond, requestedTokens);
    }

    private static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof byte[] bytes) {
            return Long.parseLong(new String(bytes));
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof byte[] bytes) {
            return new BigDecimal(new String(bytes));
        }
        return new BigDecimal(String.valueOf(value));
    }
}
