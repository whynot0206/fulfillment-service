package com.why.fulfillment.order.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record OrderRecord(Long orderId,
                          Long userId,
                          BigDecimal totalAmount,
                          Long timeoutSeconds,
                          OrderStatus status,
                          ReservationStatus reservationStatus,
                          String reservationError,
                          String outTradeNo,
                          LocalDateTime payTime,
                          LocalDateTime expireTime,
                          List<OrderItemRecord> items) {
}
