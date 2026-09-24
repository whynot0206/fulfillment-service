package com.why.fulfillment.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.services.commerce-url=http://commerce.test:18084",
                "gateway.services.order-url=http://order.test:18081",
                "gateway.services.payment-url=http://payment.test:18083",
                "gateway.services.inventory-url=http://inventory.test:18082",
                "INTERNAL_SERVICE_TOKEN=test-only-internal-token",
                // JwtTokenVerifier 在密钥缺失时拒绝启动，所以测试必须显式给一个。
                "auth.jwt.secret=test-only-secret-value-at-least-32-chars"
        })
class GatewayRoutesTest {

    @Autowired
    private RouteLocator routeLocator;

    @Test
    void exposesConfigurableRoutesForCommerceOrderPaymentAndInventory() {
        Set<String> ids = routeLocator.getRoutes().map(Route::getId).collect(Collectors.toSet()).block();

        assertThat(ids).contains("commerce-service", "order-service", "payment-service", "inventory-service");
    }
}
