package com.why.fulfillment.api.order;

import com.why.fulfillment.api.InternalFeignAuthConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Internal HTTP contract towards order-service.
 *
 * <p>Two callers share it: payment-service marks orders paid, commerce-service creates them
 * from checkout. Both endpoints sit under {@code /internal/**} and therefore require
 * {@code X-Internal-Service-Token}, which {@link InternalFeignAuthConfiguration} attaches.</p>
 */
@FeignClient(name = "order-service", url = "${order.service.url:http://localhost:18081}",
        configuration = InternalFeignAuthConfiguration.class)
public interface OrderClient {

    @PostMapping("/internal/orders/mark-paid")
    OrderMarkPaidResponse markPaid(@RequestBody OrderMarkPaidRequest request);

    @GetMapping("/internal/orders/{orderId}/payment-view")
    OrderPaymentView paymentView(@PathVariable("orderId") long orderId);

    /**
     * Creates an order with a checkout price snapshot.
     *
     * <p>Deliberately separate from the public {@code POST /api/orders}: that endpoint is the
     * measured concurrency entry point and its request shape is frozen by the existing JMeter
     * plan. Adding snapshot fields there would change what the baseline measures.</p>
     */
    @PostMapping("/internal/orders")
    OrderCreateResponse create(@RequestBody OrderCreateRequest request);

    /** Read existing order facts for the same snapshot; never creates an order or reserves stock. */
    @PostMapping("/internal/orders/resolve-create")
    OrderCreateResponse resolveCreate(@RequestBody OrderCreateRequest request);
}
