package com.why.fulfillment.payment.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** The demo endpoint must not accept a forged user header on the direct service port. */
@Component
public class PaymentMockTokenFilter extends OncePerRequestFilter {
    private final byte[] expected;

    public PaymentMockTokenFilter(@Value("${internal.service.token}") String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("INTERNAL_SERVICE_TOKEN must be configured");
        }
        expected = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().matches("/api/payments/orders/[^/]+/mock-success");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String supplied = request.getHeader("X-Internal-Service-Token");
        if (supplied == null || !MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(request, response);
    }
}
