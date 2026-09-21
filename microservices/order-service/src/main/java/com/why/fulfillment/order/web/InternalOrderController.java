package com.why.fulfillment.order.web;

import com.why.fulfillment.api.order.OrderMarkPaidRequest;
import com.why.fulfillment.api.order.OrderMarkPaidResponse;
import com.why.fulfillment.order.service.OrderApplicationService;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/orders")
public class InternalOrderController {

    private final OrderApplicationService service;

    public InternalOrderController(OrderApplicationService service) {
        this.service = service;
    }

    @PostMapping("/mark-paid")
    public OrderMarkPaidResponse markPaid(@RequestBody OrderMarkPaidRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        boolean accepted = service.markPaid(request.orderId(), request.outTradeNo());
        return new OrderMarkPaidResponse(accepted, accepted ? "PAID" : "REJECTED",
                accepted ? null : "order is not pending or trade number does not match");
    }
}
