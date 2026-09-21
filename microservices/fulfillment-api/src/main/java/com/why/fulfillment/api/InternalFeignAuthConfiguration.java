package com.why.fulfillment.api;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

public class InternalFeignAuthConfiguration {
    @Bean
    RequestInterceptor internalServiceToken(
            @Value("${internal.service.token}") String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("INTERNAL_SERVICE_TOKEN must be configured");
        }
        return template -> template.header("X-Internal-Service-Token", token);
    }
}
