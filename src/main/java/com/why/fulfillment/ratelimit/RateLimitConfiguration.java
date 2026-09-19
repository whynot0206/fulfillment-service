package com.why.fulfillment.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.why.fulfillment.inventory.redis.RedisTokenBucketService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring registration for the opt-in order rate limiter. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfiguration {

    @Bean
    public OrderRedisRateLimitFilter orderRedisRateLimitFilter(
            ObjectProvider<RedisTokenBucketService> tokenBucketServiceProvider,
            RateLimitProperties properties,
            ObjectMapper objectMapper) {
        return new OrderRedisRateLimitFilter(tokenBucketServiceProvider, properties, objectMapper);
    }
}
