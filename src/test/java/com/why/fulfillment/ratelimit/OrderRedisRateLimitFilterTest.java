package com.why.fulfillment.ratelimit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.why.fulfillment.inventory.redis.RedisTokenBucketResult;
import com.why.fulfillment.inventory.redis.RedisTokenBucketService;
import com.why.fulfillment.inventory.redis.RedisTokenBucketStatus;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderRedisRateLimitFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void consumesGlobalThenEndpointBucketBeforeContinuing() throws Exception {
        RedisTokenBucketService service = mock(RedisTokenBucketService.class);
        when(service.tryAcquire(eq("fulfillment:rate-limit:global"), eq(10L), eq(5.0D), eq(1L)))
                .thenReturn(result(true));
        when(service.tryAcquire(eq("fulfillment:rate-limit:endpoint:/api/orders/redis"),
                eq(3L), eq(2.0D), eq(1L))).thenReturn(result(true));

        RateLimitProperties properties = enabledProperties();
        properties.setGlobal(new RateLimitProperties.Bucket(10L, 5.0D));
        properties.setEndpoint(new RateLimitProperties.Bucket(3L, 2.0D));
        OrderRedisRateLimitFilter filter = new OrderRedisRateLimitFilter(service, properties, objectMapper);
        MockHttpServletRequest request = request("/api/orders/redis");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
        var calls = inOrder(service);
        calls.verify(service).tryAcquire("fulfillment:rate-limit:global", 10L, 5.0D, 1L);
        calls.verify(service).tryAcquire("fulfillment:rate-limit:endpoint:/api/orders/redis", 3L, 2.0D, 1L);
    }

    @Test
    void rejectsWhenGlobalBucketIsExhaustedAndSkipsEndpointBucket() throws Exception {
        RedisTokenBucketService service = mock(RedisTokenBucketService.class);
        when(service.tryAcquire(eq("fulfillment:rate-limit:global"), anyLong(), anyDouble(), anyLong()))
                .thenReturn(result(false));
        OrderRedisRateLimitFilter filter = new OrderRedisRateLimitFilter(service, enabledProperties(), objectMapper);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("/api/orders/redis"), response, new MockFilterChain());

        assertRateLimitedResponse(response);
        verify(service, never()).tryAcquire(eq("fulfillment:rate-limit:endpoint:/api/orders/redis"),
                anyLong(), anyDouble(), anyLong());
    }

    @Test
    void rejectsWhenEndpointBucketIsExhaustedAfterGlobalPasses() throws Exception {
        RedisTokenBucketService service = mock(RedisTokenBucketService.class);
        when(service.tryAcquire(eq("fulfillment:rate-limit:global"), anyLong(), anyDouble(), anyLong()))
                .thenReturn(result(true));
        when(service.tryAcquire(eq("fulfillment:rate-limit:endpoint:/api/orders/redis"),
                anyLong(), anyDouble(), anyLong())).thenReturn(result(false));
        OrderRedisRateLimitFilter filter = new OrderRedisRateLimitFilter(service, enabledProperties(), objectMapper);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("/api/orders/redis"), response, new MockFilterChain());

        assertRateLimitedResponse(response);
    }

    @Test
    void doesNotCallRedisWhenDisabledOrPathDoesNotMatch() throws Exception {
        RedisTokenBucketService service = mock(RedisTokenBucketService.class);
        RateLimitProperties disabled = enabledProperties();
        disabled.setEnabled(false);
        OrderRedisRateLimitFilter filter = new OrderRedisRateLimitFilter(service, disabled, objectMapper);
        MockFilterChain disabledChain = new MockFilterChain();
        filter.doFilter(request("/api/orders/redis"), new MockHttpServletResponse(), disabledChain);

        RateLimitProperties enabled = enabledProperties();
        OrderRedisRateLimitFilter pathScopedFilter = new OrderRedisRateLimitFilter(service, enabled, objectMapper);
        MockFilterChain otherPathChain = new MockFilterChain();
        pathScopedFilter.doFilter(request("/api/orders"), new MockHttpServletResponse(), otherPathChain);

        verify(service, never()).tryAcquire(anyString(), anyLong(), anyDouble(), anyLong());
        assertNotNull(disabledChain.getRequest());
        assertNotNull(otherPathChain.getRequest());
    }

    private RateLimitProperties enabledProperties() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setPermits(1L);
        return properties;
    }

    private MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(path);
        return request;
    }

    private RedisTokenBucketResult result(boolean allowed) {
        return new RedisTokenBucketResult(
                allowed,
                allowed ? RedisTokenBucketStatus.ALLOWED : RedisTokenBucketStatus.REJECTED,
                java.math.BigDecimal.ONE,
                100L,
                java.math.BigDecimal.ONE,
                1L);
    }

    private void assertRateLimitedResponse(MockHttpServletResponse response) throws Exception {
        assertEquals(429, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertFalse(body.get("success").asBoolean());
        assertTrue(body.get("data").isNull());
        assertEquals(OrderRedisRateLimitFilter.DEFAULT_REJECTION_MESSAGE, body.get("message").asText());
    }
}
