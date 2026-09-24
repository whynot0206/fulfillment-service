package com.why.fulfillment.order.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InternalServiceTokenFilterTest {

    private final InternalServiceTokenFilter filter = new InternalServiceTokenFilter("local-test-token");

    @Test
    void directOrderReadWithForgedUserHeaderIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders/123");
        request.addHeader("X-User-Id", "999");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
    }

    @Test
    void gatewayOrderRequestWithInternalTokenPasses() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders/123");
        request.addHeader("X-Internal-Service-Token", "local-test-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }

    @Test
    void healthEndpointRemainsAccessible() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }
}
