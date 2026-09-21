package com.why.fulfillment.api.order;

import com.why.fulfillment.api.InternalFeignAuthConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** Internal payment-to-order HTTP contract. */
@FeignClient(name = "order-service", url = "${order.service.url:http://localhost:18081}",
        configuration = InternalFeignAuthConfiguration.class)
public interface OrderClient {

    @PostMapping("/internal/orders/mark-paid")
    OrderMarkPaidResponse markPaid(@RequestBody OrderMarkPaidRequest request);
}
