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
                "gateway.services.order-url=http://order.test:18081",
                "gateway.services.payment-url=http://payment.test:18083",
                "gateway.services.inventory-url=http://inventory.test:18082"
        })
class GatewayRoutesTest {

    @Autowired
    private RouteLocator routeLocator;

    @Test
    void exposesConfigurableRoutesForOrderPaymentAndInventory() {
        Set<String> ids = routeLocator.getRoutes().map(Route::getId).collect(Collectors.toSet()).block();

        assertThat(ids).contains("order-service", "payment-service", "inventory-service");
    }
}
