package com.why.fulfillment.payment.service;

public interface PaymentCallbackService {

    boolean acceptSuccess(Long orderId, String outTradeNo);
}
