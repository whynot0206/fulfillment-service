package com.why.fulfillment.order.repository;

import com.why.fulfillment.order.domain.OrderRecord;
import com.why.fulfillment.order.domain.OrderStatus;
import com.why.fulfillment.order.domain.ReservationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;

@Repository
public class OrderRepository {

    private final JdbcTemplate jdbcTemplate;

    public OrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Only local writes are transactional; the inventory call is deliberately outside this method. */
    @Transactional
    public void insertPending(long orderId, long userId, BigDecimal totalAmount) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO `order` (order_id, user_id, total_amount, status,
                                     reservation_status, reservation_error)
                VALUES (?, ?, ?, ?, ?, NULL)
                """, orderId, userId, totalAmount, OrderStatus.PENDING_PAYMENT.code(),
                ReservationStatus.RESERVING.code());
        if (inserted != 1) {
            throw new IllegalStateException("failed to create order " + orderId);
        }
    }

    /** Transition is guarded by the current status so retries do not overwrite a later decision. */
    @Transactional
    public boolean updateReservation(long orderId, ReservationStatus status, String error) {
        return jdbcTemplate.update("""
                UPDATE `order`
                   SET reservation_status = ?, reservation_error = ?,
                       status = CASE WHEN ? IN (?, ?) THEN ? ELSE status END,
                       update_time = CURRENT_TIMESTAMP
                 WHERE order_id = ? AND reservation_status = ?
                """, status.code(), truncate(error), status.code(),
                ReservationStatus.COMPENSATED.code(), ReservationStatus.FAILED.code(),
                OrderStatus.CANCELED.code(), orderId,
                ReservationStatus.RESERVING.code()) == 1;
    }

    @Transactional
    public boolean markCompensationPending(long orderId, String error) {
        return jdbcTemplate.update("""
                UPDATE `order`
                   SET reservation_status = ?, reservation_error = ?, update_time = CURRENT_TIMESTAMP
                 WHERE order_id = ? AND reservation_status IN (?, ?)
                """, ReservationStatus.PENDING_COMPENSATION.code(), truncate(error), orderId,
                ReservationStatus.RESERVING.code(), ReservationStatus.PENDING_COMPENSATION.code()) == 1;
    }

    public List<Long> findPendingCompensationIds(int limit) {
        return jdbcTemplate.queryForList("""
                SELECT order_id FROM `order`
                 WHERE reservation_status = ?
                 ORDER BY update_time, order_id LIMIT ?
                """, Long.class, ReservationStatus.PENDING_COMPENSATION.code(), limit);
    }

    @Transactional
    public boolean markCompensatedIfPending(long orderId, String error) {
        return jdbcTemplate.update("""
                UPDATE `order`
                   SET reservation_status = ?, reservation_error = ?, status = ?,
                       update_time = CURRENT_TIMESTAMP
                 WHERE order_id = ? AND reservation_status = ?
                """, ReservationStatus.COMPENSATED.code(), truncate(error),
                OrderStatus.CANCELED.code(), orderId,
                ReservationStatus.PENDING_COMPENSATION.code()) == 1;
    }

    /** Payment and its inventory-confirmation event commit in one local transaction. */
    @Transactional
    public boolean markPaidIfPending(long orderId, String outTradeNo) {
        int updated = jdbcTemplate.update("""
                UPDATE `order`
                   SET status = ?, out_trade_no = ?, pay_time = CURRENT_TIMESTAMP,
                       update_time = CURRENT_TIMESTAMP
                 WHERE order_id = ? AND status = ? AND reservation_status = ?
                """, OrderStatus.PAID.code(), outTradeNo, orderId,
                OrderStatus.PENDING_PAYMENT.code(), ReservationStatus.RESERVED.code());
        if (updated == 1) {
            jdbcTemplate.update("""
                    INSERT INTO order_outbox_event
                        (event_type, biz_key, payload, status, retry_count, next_retry_time)
                    VALUES ('PAYMENT_CONFIRMED', ?, ?, 0, 0, CURRENT_TIMESTAMP)
                    """, Long.toString(orderId), "{\"orderId\":" + orderId + "}");
        }
        return updated == 1;
    }

    public List<Long> findReadyConfirmationEventIds(int limit) {
        return jdbcTemplate.queryForList("""
                SELECT event_id FROM order_outbox_event
                 WHERE event_type = 'PAYMENT_CONFIRMED'
                   AND status = 0 AND next_retry_time <= CURRENT_TIMESTAMP
                 ORDER BY event_id LIMIT ?
                """, Long.class, limit);
    }

    @Transactional
    public Optional<Long> claimConfirmationEvent(long eventId) {
        int claimed = jdbcTemplate.update("""
                UPDATE order_outbox_event SET status = 1, update_time = CURRENT_TIMESTAMP
                 WHERE event_id = ? AND status = 0 AND next_retry_time <= CURRENT_TIMESTAMP
                """, eventId);
        if (claimed != 1) {
            return Optional.empty();
        }
        return jdbcTemplate.queryForList(
                "SELECT CAST(biz_key AS UNSIGNED) FROM order_outbox_event WHERE event_id = ?",
                Long.class, eventId).stream().findFirst();
    }

    public void markConfirmationSent(long eventId) {
        jdbcTemplate.update("""
                UPDATE order_outbox_event SET status = 2, last_error = NULL,
                       update_time = CURRENT_TIMESTAMP WHERE event_id = ? AND status = 1
                """, eventId);
    }

    public void retryConfirmation(long eventId, String error) {
        jdbcTemplate.update("""
                UPDATE order_outbox_event
                   SET status = CASE WHEN retry_count >= 9 THEN 3 ELSE 0 END,
                       retry_count = retry_count + 1,
                       next_retry_time = DATE_ADD(CURRENT_TIMESTAMP,
                           INTERVAL LEAST(300, POW(2, LEAST(retry_count, 8))) SECOND),
                       last_error = ?, update_time = CURRENT_TIMESTAMP
                 WHERE event_id = ? AND status = 1
                """, truncate(error), eventId);
    }

    public void recoverStaleConfirmationClaims() {
        jdbcTemplate.update("""
                UPDATE order_outbox_event SET status = 0, update_time = CURRENT_TIMESTAMP
                 WHERE event_type = 'PAYMENT_CONFIRMED' AND status = 1
                   AND update_time < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 MINUTE)
                """);
    }

    public Optional<OrderRecord> find(long orderId) {
        return jdbcTemplate.query("""
                SELECT order_id, user_id, total_amount, status,
                       reservation_status, reservation_error, out_trade_no, pay_time
                  FROM `order` WHERE order_id = ?
                """, this::map, orderId).stream().findFirst();
    }

    private OrderRecord map(ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new OrderRecord(
                rs.getLong("order_id"),
                rs.getLong("user_id"),
                rs.getBigDecimal("total_amount"),
                toOrderStatus(rs.getInt("status")),
                ReservationStatus.fromCode(rs.getInt("reservation_status")),
                rs.getString("reservation_error"),
                rs.getString("out_trade_no"),
                rs.getObject("pay_time", LocalDateTime.class));
    }

    private static OrderStatus toOrderStatus(int code) {
        for (OrderStatus status : OrderStatus.values()) {
            if (status.code() == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown order status " + code);
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= 500) {
            return error;
        }
        return error.substring(0, 500);
    }
}
