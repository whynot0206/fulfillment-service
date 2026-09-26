package com.why.fulfillment.order.domain;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * An order and its lines.
 * Order IDs are JSON strings so browsers preserve all digits; Java keeps the Long type.
 *
 * <p>{@code createTime} is appended last and is nullable so the eleven-argument form used by
 * the existing tests and by the reservation paths keeps compiling. It is filled on read; the
 * write paths never set it, because the column is owned by MySQL's
 * {@code DEFAULT CURRENT_TIMESTAMP} — one clock, not one per application node.</p>
 */
public record OrderRecord(@JsonSerialize(using = ToStringSerializer.class) Long orderId,
                          Long userId,
                          BigDecimal totalAmount,
                          Long timeoutSeconds,
                          OrderStatus status,
                          ReservationStatus reservationStatus,
                          String reservationError,
                          String outTradeNo,
                          LocalDateTime payTime,
                          LocalDateTime expireTime,
                          List<OrderItemRecord> items,
                          LocalDateTime createTime) {

    public OrderRecord(Long orderId, Long userId, BigDecimal totalAmount, Long timeoutSeconds,
                       OrderStatus status, ReservationStatus reservationStatus, String reservationError,
                       String outTradeNo, LocalDateTime payTime, LocalDateTime expireTime,
                       List<OrderItemRecord> items) {
        this(orderId, userId, totalAmount, timeoutSeconds, status, reservationStatus, reservationError,
                outTradeNo, payTime, expireTime, items, null);
    }

    /** Same order, different line list. Used when items are loaded in a second query. */
    public OrderRecord withItems(List<OrderItemRecord> replacement) {
        return new OrderRecord(orderId, userId, totalAmount, timeoutSeconds, status, reservationStatus,
                reservationError, outTradeNo, payTime, expireTime, replacement, createTime);
    }
}
