package com.why.fulfillment.order.repository;

import com.why.fulfillment.order.domain.OrderRecord;
import com.why.fulfillment.order.domain.OrderItemRecord;
import com.why.fulfillment.order.domain.OrderStatus;
import com.why.fulfillment.order.domain.ReservationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
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
    public void insertPending(long orderId, long userId, BigDecimal totalAmount, long timeoutSeconds,
                              List<OrderItemRecord> items) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO `order` (order_id, user_id, total_amount, timeout_seconds,
                                     status, reservation_status, reservation_error, expire_time)
                VALUES (?, ?, ?, ?, ?, ?, NULL,
                        TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP))
                """, orderId, userId, totalAmount, timeoutSeconds,
                OrderStatus.PENDING_PAYMENT.code(),
                ReservationStatus.RESERVING.code(), timeoutSeconds);
        if (inserted != 1) {
            throw new IllegalStateException("failed to create order " + orderId);
        }
        for (OrderItemRecord item : items) {
            jdbcTemplate.update("""
                    INSERT INTO order_item (order_id, sku_id, spu_id, `count`, price,
                                            name_snapshot, spec_snapshot)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, orderId, item.skuId(), item.spuId(), item.count(), item.price(),
                    item.nameSnapshot(), item.specSnapshot());
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
                 WHERE order_id = ? AND reservation_status = ? AND status = ?
                """, status.code(), truncate(error), status.code(),
                ReservationStatus.COMPENSATED.code(), ReservationStatus.FAILED.code(),
                OrderStatus.CANCELED.code(), orderId,
                ReservationStatus.RESERVING.code(), OrderStatus.PENDING_PAYMENT.code()) == 1;
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

    public List<Long> findExpiredReservedOrderIds(int limit) {
        return jdbcTemplate.queryForList("""
                SELECT order_id FROM `order`
                 WHERE status = ? AND reservation_status = ?
                   AND expire_time <= CURRENT_TIMESTAMP
                 ORDER BY expire_time, order_id LIMIT ?
                """, Long.class, OrderStatus.PENDING_PAYMENT.code(),
                ReservationStatus.RESERVED.code(), limit);
    }

    @Transactional
    public boolean markExpiredForCompensation(long orderId) {
        return jdbcTemplate.update("""
                UPDATE `order`
                   SET status = ?, reservation_status = ?,
                       reservation_error = 'payment timeout; inventory release pending',
                       update_time = CURRENT_TIMESTAMP
                 WHERE order_id = ? AND status = ? AND reservation_status = ?
                   AND expire_time <= CURRENT_TIMESTAMP
                """, OrderStatus.CANCELED.code(), ReservationStatus.PENDING_COMPENSATION.code(),
                orderId, OrderStatus.PENDING_PAYMENT.code(), ReservationStatus.RESERVED.code()) == 1;
    }

    /** Competes with payment on the order row; the background worker owns release retries. */
    @Transactional
    public boolean markUserCanceledForCompensation(long orderId, long userId) {
        return jdbcTemplate.update("""
                UPDATE `order`
                   SET status = ?, reservation_status = ?,
                       reservation_error = 'user canceled; inventory release pending',
                       update_time = CURRENT_TIMESTAMP
                 WHERE order_id = ? AND user_id = ? AND status = ?
                   AND reservation_status IN (?, ?)
                """, OrderStatus.CANCELED.code(), ReservationStatus.PENDING_COMPENSATION.code(),
                orderId, userId, OrderStatus.PENDING_PAYMENT.code(),
                ReservationStatus.RESERVING.code(), ReservationStatus.RESERVED.code()) == 1;
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

    public Map<Integer, Long> countConfirmationEventsByStatus() {
        return jdbcTemplate.query("""
                SELECT status, COUNT(*) AS status_count
                  FROM order_outbox_event
                 WHERE event_type = 'PAYMENT_CONFIRMED'
                 GROUP BY status
                """, rs -> {
            Map<Integer, Long> counts = new LinkedHashMap<>();
            while (rs.next()) {
                counts.put(rs.getInt("status"), rs.getLong("status_count"));
            }
            return counts;
        });
    }

    public Optional<OrderRecord> find(long orderId) {
        return jdbcTemplate.query("""
                SELECT order_id, user_id, total_amount, timeout_seconds, status,
                       reservation_status, reservation_error, out_trade_no, pay_time, expire_time,
                       create_time
                  FROM `order` WHERE order_id = ?
                """, this::map, orderId).stream().findFirst()
                .map(record -> record.withItems(findItems(List.of(orderId))
                        .getOrDefault(orderId, List.of())));
    }

    /**
     * One page of a user's orders, newest first.
     *
     * <p>Ordering repeats the index column order so MySQL can walk
     * {@code idx_order_user_time} backwards instead of sorting. {@code order_id} is in the
     * ORDER BY as a tiebreaker: without it, two orders created in the same second can swap
     * places between two page requests, which makes a row appear twice or not at all.</p>
     *
     * <p>Paging is OFFSET based. That is honest for a personal order list, where the offset
     * stays small; it is <b>not</b> what a deep, unbounded listing should use, because MySQL
     * still walks and discards the skipped rows. The caller bounds the offset (see
     * {@code OrderApplicationService}) rather than leaving that cost open-ended.</p>
     */
    public List<OrderRecord> findByUser(long userId, int limit, int offset) {
        List<OrderRecord> orders = jdbcTemplate.query("""
                SELECT order_id, user_id, total_amount, timeout_seconds, status,
                       reservation_status, reservation_error, out_trade_no, pay_time, expire_time,
                       create_time
                  FROM `order`
                 WHERE user_id = ?
                 ORDER BY create_time DESC, order_id DESC
                 LIMIT ? OFFSET ?
                """, this::map, userId, limit, offset);
        if (orders.isEmpty()) {
            return List.of();
        }
        // One IN query for every line on the page instead of one query per order.
        // With 20 orders per page the N+1 version is 21 round trips; this is 2.
        Map<Long, List<OrderItemRecord>> itemsByOrder =
                findItems(orders.stream().map(OrderRecord::orderId).toList());
        return orders.stream()
                .map(order -> order.withItems(itemsByOrder.getOrDefault(order.orderId(), List.of())))
                .toList();
    }

    public long countByUser(long userId) {
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `order` WHERE user_id = ?", Long.class, userId);
        return total == null ? 0L : total;
    }

    private Map<Long, List<OrderItemRecord>> findItems(List<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(orderIds.size(), "?"));
        List<Map.Entry<Long, OrderItemRecord>> rows = jdbcTemplate.query("""
                SELECT order_id, sku_id, spu_id, `count`, price, name_snapshot, spec_snapshot
                  FROM order_item WHERE order_id IN (%s) ORDER BY order_id, sku_id
                """.formatted(placeholders),
                (rs, rowNum) -> Map.entry(rs.getLong("order_id"), new OrderItemRecord(
                        rs.getLong("sku_id"), rs.getLong("spu_id"),
                        rs.getInt("count"), rs.getBigDecimal("price"),
                        rs.getString("name_snapshot"), rs.getString("spec_snapshot"))),
                orderIds.toArray());
        Map<Long, List<OrderItemRecord>> grouped = new LinkedHashMap<>();
        for (Map.Entry<Long, OrderItemRecord> row : rows) {
            grouped.computeIfAbsent(row.getKey(), key -> new ArrayList<>()).add(row.getValue());
        }
        return grouped;
    }

    private OrderRecord map(ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new OrderRecord(
                rs.getLong("order_id"),
                rs.getLong("user_id"),
                rs.getBigDecimal("total_amount"),
                rs.getLong("timeout_seconds"),
                toOrderStatus(rs.getInt("status")),
                ReservationStatus.fromCode(rs.getInt("reservation_status")),
                rs.getString("reservation_error"),
                rs.getString("out_trade_no"),
                rs.getObject("pay_time", LocalDateTime.class),
                rs.getObject("expire_time", LocalDateTime.class),
                List.of(),
                rs.getObject("create_time", LocalDateTime.class));
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
