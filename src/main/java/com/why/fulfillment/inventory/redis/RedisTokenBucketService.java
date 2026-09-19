package com.why.fulfillment.inventory.redis;

import java.math.BigDecimal;

/**
 * Redis Lua token bucket used by a gateway or an application endpoint.
 */
public interface RedisTokenBucketService {

    /**
     * Attempt to take {@code requestedTokens} from a bucket.
     *
     * @param bucketName logical bucket name; the implementation adds its key
     *                   namespace
     * @param capacity maximum number of tokens
     * @param refillRatePerSecond tokens added per second, including fractions
     * @param requestedTokens positive number of tokens requested
     */
    RedisTokenBucketResult tryAcquire(String bucketName,
                                      long capacity,
                                      BigDecimal refillRatePerSecond,
                                      long requestedTokens);

    /** Convenience overload for ordinary decimal rates. */
    default RedisTokenBucketResult tryAcquire(String bucketName,
                                              long capacity,
                                              double refillRatePerSecond,
                                              long requestedTokens) {
        return tryAcquire(bucketName, capacity,
                BigDecimal.valueOf(refillRatePerSecond), requestedTokens);
    }

    /** Return the physical Redis key for cleanup/observability. */
    String bucketKey(String bucketName);
}
