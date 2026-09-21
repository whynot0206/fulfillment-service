package com.why.fulfillment.api.order;

public record OrderMarkPaidRequest(Long orderId, String outTradeNo) {
}
