package com.why.fulfillment.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the two token buckets guarding the Redis order path.
 *
 * <p>Rate limiting is disabled by default so that a baseline benchmark can be
 * run without changing the request path.  Enable it explicitly with
 * {@code fulfillment.rate-limit.enabled=true}.</p>
 */
@ConfigurationProperties(prefix = "fulfillment.rate-limit")
public class RateLimitProperties {

    private boolean enabled;
    private String path = "/api/orders/redis";
    private long permits = 1L;
    private String globalKey = "fulfillment:rate-limit:global";
    private String endpointKeyPrefix = "fulfillment:rate-limit:endpoint:";
    private Bucket global = new Bucket(100L, 100.0D);
    private Bucket endpoint = new Bucket(20L, 20.0D);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    /**
     * Alias that makes programmatic configuration read naturally while the
     * YAML property remains {@code path} for backwards compatible binding.
     */
    public String getEndpointPath() {
        return path;
    }

    public void setEndpointPath(String endpointPath) {
        this.path = endpointPath;
    }

    public long getPermits() {
        return permits;
    }

    public void setPermits(long permits) {
        this.permits = permits;
    }

    public String getGlobalKey() {
        return globalKey;
    }

    public void setGlobalKey(String globalKey) {
        this.globalKey = globalKey;
    }

    public String getEndpointKeyPrefix() {
        return endpointKeyPrefix;
    }

    public void setEndpointKeyPrefix(String endpointKeyPrefix) {
        this.endpointKeyPrefix = endpointKeyPrefix;
    }

    public Bucket getGlobal() {
        return global;
    }

    public void setGlobal(Bucket global) {
        this.global = global;
    }

    public Bucket getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(Bucket endpoint) {
        this.endpoint = endpoint;
    }

    public static class Bucket {

        private long capacity;
        private double refillPerSecond;

        public Bucket() {
        }

        public Bucket(long capacity, double refillPerSecond) {
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
        }

        public long getCapacity() {
            return capacity;
        }

        public void setCapacity(long capacity) {
            this.capacity = capacity;
        }

        public double getRefillPerSecond() {
            return refillPerSecond;
        }

        public void setRefillPerSecond(double refillPerSecond) {
            this.refillPerSecond = refillPerSecond;
        }
    }
}
