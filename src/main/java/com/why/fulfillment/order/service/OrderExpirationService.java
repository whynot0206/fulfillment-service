package com.why.fulfillment.order.service;

public interface OrderExpirationService {

    boolean expire(Long orderId);
}
