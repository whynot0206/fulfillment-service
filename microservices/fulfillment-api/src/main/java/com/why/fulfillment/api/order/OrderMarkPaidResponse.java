package com.why.fulfillment.api.order;

public record OrderMarkPaidResponse(boolean accepted, String status, String error) {
}
