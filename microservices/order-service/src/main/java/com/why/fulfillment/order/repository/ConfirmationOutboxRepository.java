package com.why.fulfillment.order.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Delivery state only; payment and event creation remain in OrderRepository's local transaction. */
@Repository
public class ConfirmationOutboxRepository {
    private static final int LEASE_SECONDS = 60;
    private final JdbcTemplate jdbcTemplate;

    public ConfirmationOutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
    public Optional<ConfirmationClaim> claimConfirmationEvent(long eventId) {
        // A token belongs to one attempt, not a process. An old attempt cannot finish a newer lease.
        String owner = UUID.randomUUID().toString();
        int claimed = jdbcTemplate.update("""
                UPDATE order_outbox_event
                   SET status = 1, lease_owner = ?,
                       lease_until = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL ? SECOND),
                       update_time = CURRENT_TIMESTAMP
                 WHERE event_id = ? AND event_type = 'PAYMENT_CONFIRMED'
                   AND status = 0 AND next_retry_time <= CURRENT_TIMESTAMP
                """, owner, LEASE_SECONDS, eventId);
        if (claimed != 1) {
            return Optional.empty();
        }
        String bizKey = jdbcTemplate.queryForObject("""
                SELECT biz_key FROM order_outbox_event
                 WHERE event_id = ? AND status = 1 AND lease_owner = ?
                """, String.class, eventId, owner);
        long orderId = Long.parseLong(bizKey);
        if (orderId <= 0) {
            throw new IllegalStateException("invalid payment confirmation business key");
        }
        return Optional.of(new ConfirmationClaim(eventId, orderId, owner));
    }

    public boolean markConfirmationSent(long eventId, String owner) {
        return jdbcTemplate.update("""
                UPDATE order_outbox_event
                   SET status = 2, last_error = NULL, lease_owner = NULL, lease_until = NULL,
                       update_time = CURRENT_TIMESTAMP
                 WHERE event_id = ? AND event_type = 'PAYMENT_CONFIRMED' AND status = 1
                   AND lease_owner = ? AND lease_until > CURRENT_TIMESTAMP(6)
                """, eventId, owner) == 1;
    }

    public boolean retryConfirmation(long eventId, String owner, String error) {
        return jdbcTemplate.update("""
                UPDATE order_outbox_event
                   SET status = CASE WHEN retry_count >= 9 THEN 3 ELSE 0 END,
                       next_retry_time = DATE_ADD(CURRENT_TIMESTAMP,
                           INTERVAL LEAST(300, POW(2, LEAST(retry_count + 1, 8))) SECOND),
                       retry_count = retry_count + 1,
                       last_error = ?, lease_owner = NULL, lease_until = NULL,
                       update_time = CURRENT_TIMESTAMP
                 WHERE event_id = ? AND event_type = 'PAYMENT_CONFIRMED' AND status = 1
                   AND lease_owner = ? AND lease_until > CURRENT_TIMESTAMP(6)
                """, truncate(error), eventId, owner) == 1;
    }

    public int recoverStaleConfirmationClaims() {
        // Null leases are pre-migration claims. Stop old publishers before deploying this version.
        return jdbcTemplate.update("""
                UPDATE order_outbox_event
                   SET status = 0, lease_owner = NULL, lease_until = NULL,
                       update_time = CURRENT_TIMESTAMP
                 WHERE event_type = 'PAYMENT_CONFIRMED' AND status = 1
                   AND (lease_until <= CURRENT_TIMESTAMP(6)
                        OR (lease_until IS NULL
                            AND update_time < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 MINUTE)))
                """);
    }

    public Map<Integer, Long> countConfirmationEventsByStatus() {
        return jdbcTemplate.query("""
                SELECT status, COUNT(*) AS status_count FROM order_outbox_event
                 WHERE event_type = 'PAYMENT_CONFIRMED' GROUP BY status
                """, rs -> {
            Map<Integer, Long> counts = new LinkedHashMap<>();
            while (rs.next()) {
                counts.put(rs.getInt("status"), rs.getLong("status_count"));
            }
            return counts;
        });
    }

    private static String truncate(String error) {
        return error == null ? null : error.substring(0, Math.min(error.length(), 500));
    }

    public record ConfirmationClaim(long eventId, long orderId, String leaseOwner) { }
}
