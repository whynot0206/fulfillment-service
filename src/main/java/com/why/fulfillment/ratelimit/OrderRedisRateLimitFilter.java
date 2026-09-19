package com.why.fulfillment.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.why.fulfillment.inventory.redis.RedisTokenBucketResult;
import com.why.fulfillment.inventory.redis.RedisTokenBucketService;
import com.why.fulfillment.web.ApiResponse;
import com.why.fulfillment.observability.FulfillmentMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Applies global and endpoint token buckets to the Redis order endpoint.
 *
 * <p>This is deliberately scoped to one exact path.  Existing order traffic
 * keeps its current behavior and can be used as the no-rate-limit benchmark
 * baseline.</p>
 */
public class OrderRedisRateLimitFilter extends OncePerRequestFilter {

    public static final String DEFAULT_REJECTION_MESSAGE = "rate limit exceeded";

    private final ObjectProvider<RedisTokenBucketService> tokenBucketServiceProvider;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;
    private final FulfillmentMetrics metrics;

    /** Constructor used by Spring's configuration. */
    public OrderRedisRateLimitFilter(ObjectProvider<RedisTokenBucketService> tokenBucketServiceProvider,
                                     RateLimitProperties properties,
                                     ObjectMapper objectMapper,
                                     FulfillmentMetrics metrics) {
        this.tokenBucketServiceProvider = tokenBucketServiceProvider;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    /** Convenience constructor for focused filter tests and embedding code. */
    public OrderRedisRateLimitFilter(RedisTokenBucketService tokenBucketService,
                                     RateLimitProperties properties,
                                     ObjectMapper objectMapper) {
        this(new SingleServiceProvider(tokenBucketService), properties, objectMapper,
                FulfillmentMetrics.noop());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.isEnabled() || !matchesConfiguredPath(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        RedisTokenBucketService tokenBucketService = tokenBucketServiceProvider.getIfAvailable();
        if (tokenBucketService == null) {
            // The Redis implementation is supplied by the Redis integration.
            // Keeping the filter fail-open while it is absent preserves the
            // disabled/baseline profile and lets the web slice start in tests.
            filterChain.doFilter(request, response);
            return;
        }

        RedisTokenBucketResult globalResult = tokenBucketService.tryAcquire(
                properties.getGlobalKey(),
                properties.getGlobal().getCapacity(),
                properties.getGlobal().getRefillPerSecond(),
                properties.getPermits());
        if (globalResult == null || !globalResult.isAllowed()) {
            writeRateLimitedResponse(response, "global");
            return;
        }

        String endpointKey = properties.getEndpointKeyPrefix() + configuredPath();
        RedisTokenBucketResult endpointResult = tokenBucketService.tryAcquire(
                endpointKey,
                properties.getEndpoint().getCapacity(),
                properties.getEndpoint().getRefillPerSecond(),
                properties.getPermits());
        if (endpointResult == null || !endpointResult.isAllowed()) {
            writeRateLimitedResponse(response, "endpoint");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean matchesConfiguredPath(HttpServletRequest request) {
        String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
        return (contextPath + configuredPath()).equals(request.getRequestURI());
    }

    private String configuredPath() {
        String path = properties.getPath();
        if (path == null || path.isBlank()) {
            return "/api/orders/redis";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private void writeRateLimitedResponse(HttpServletResponse response, String layer) throws IOException {
        metrics.rateLimited(layer);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(DEFAULT_REJECTION_MESSAGE));
    }

    /** Minimal ObjectProvider implementation for the direct constructor. */
    private static final class SingleServiceProvider implements ObjectProvider<RedisTokenBucketService> {

        private final RedisTokenBucketService service;

        private SingleServiceProvider(RedisTokenBucketService service) {
            this.service = service;
        }

        @Override
        public RedisTokenBucketService getObject() {
            return service;
        }

        @Override
        public RedisTokenBucketService getObject(Object... args) {
            return service;
        }

        @Override
        public RedisTokenBucketService getIfAvailable() {
            return service;
        }

        @Override
        public RedisTokenBucketService getIfUnique() {
            return service;
        }

        @Override
        public java.util.Iterator<RedisTokenBucketService> iterator() {
            return java.util.Collections.singleton(service).iterator();
        }
    }
}
