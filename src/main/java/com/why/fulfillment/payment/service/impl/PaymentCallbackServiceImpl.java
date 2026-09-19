package com.why.fulfillment.payment.service.impl;

import com.why.fulfillment.order.service.OrderService;
import com.why.fulfillment.payment.service.PaymentCallbackService;
import org.springframework.stereotype.Service;

@Service
public class PaymentCallbackServiceImpl implements PaymentCallbackService {

    private final OrderService orderService;

    public PaymentCallbackServiceImpl(OrderService orderService) {
        this.orderService = orderService;
    }

    @Override
    public boolean acceptSuccess(Long orderId, String outTradeNo) {
        return orderService.markPaid(orderId, outTradeNo);
    }
}
