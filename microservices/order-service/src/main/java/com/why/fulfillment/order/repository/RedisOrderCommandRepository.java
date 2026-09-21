package com.why.fulfillment.order.repository;

import com.why.fulfillment.order.domain.RedisOrderCommand;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class RedisOrderCommandRepository {

    private static final int MAX_ERROR_LENGTH = 500;
    private final JdbcTemplate jdbcTemplate;

    public RedisOrderCommandRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean insertPreparing(long orderId, long userId, BigDecimal totalAmount,
                                   long timeoutSeconds, String itemsJson) {
        return jdbcTemplate.update("""
                INSERT IGNORE INTO microservice_order_command
                    (order_id,user_id,total_amount,timeout_seconds,items_json,status,
                     redis_reserved,retry_count,next_retry_time)
                VALUES (?,?,?,?,?,0,0,0,CURRENT_TIMESTAMP)
                """, orderId, userId, totalAmount, timeoutSeconds, itemsJson) == 1;
    }

    public Optional<RedisOrderCommand> findByOrderId(long orderId) {
        return jdbcTemplate.query("""
                SELECT command_id,order_id,user_id,total_amount,timeout_seconds,items_json,
                       status,redis_reserved,retry_count,next_retry_time,lease_owner,lease_until,last_error
                  FROM microservice_order_command WHERE order_id=?
                """, this::map, orderId).stream().findFirst();
    }

    public List<RedisOrderCommand> findPreparing(int limit) {
        return jdbcTemplate.query("""
                SELECT command_id,order_id,user_id,total_amount,timeout_seconds,items_json,
                       status,redis_reserved,retry_count,next_retry_time,lease_owner,lease_until,last_error
                  FROM microservice_order_command
                 WHERE status=0 AND next_retry_time<=CURRENT_TIMESTAMP
                 ORDER BY next_retry_time,command_id LIMIT ?
                """, this::map, limit);
    }

    public boolean markReadyIfPreparing(long commandId) {
        return jdbcTemplate.update("""
                UPDATE microservice_order_command
                   SET status=1,redis_reserved=1,last_error=NULL,next_retry_time=CURRENT_TIMESTAMP,
                       update_time=CURRENT_TIMESTAMP
                 WHERE command_id=? AND status=0
                """, commandId) == 1;
    }

    public boolean retryPreparing(long commandId, int retryCount, LocalDateTime nextRetry, String error) {
        return jdbcTemplate.update("""
                UPDATE microservice_order_command
                   SET retry_count=?,next_retry_time=?,last_error=?,update_time=CURRENT_TIMESTAMP
                 WHERE command_id=? AND status=0
                """, retryCount, nextRetry, truncate(error), commandId) == 1;
    }

    public List<RedisOrderCommand> findReady(int limit) {
        return jdbcTemplate.query("""
                SELECT command_id,order_id,user_id,total_amount,timeout_seconds,items_json,
                       status,redis_reserved,retry_count,next_retry_time,lease_owner,lease_until,last_error
                  FROM microservice_order_command
                 WHERE status IN (1,2) AND next_retry_time<=CURRENT_TIMESTAMP
                   AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP)
                 ORDER BY next_retry_time,command_id LIMIT ?
                """, this::map, limit);
    }

    public boolean claim(long commandId, String owner, LocalDateTime leaseUntil) {
        return jdbcTemplate.update("""
                UPDATE microservice_order_command
                   SET status=2,lease_owner=?,lease_until=?,update_time=CURRENT_TIMESTAMP
                 WHERE command_id=? AND status IN (1,2) AND next_retry_time<=CURRENT_TIMESTAMP
                   AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP)
                """, owner, leaseUntil, commandId) == 1;
    }

    public boolean markSucceeded(long commandId, String owner) {
        return jdbcTemplate.update("""
                UPDATE microservice_order_command
                   SET status=3,lease_owner=NULL,lease_until=NULL,last_error=NULL,
                       update_time=CURRENT_TIMESTAMP
                 WHERE command_id=? AND status=2 AND lease_owner=?
                """, commandId, owner) == 1;
    }

    public boolean scheduleRetry(long commandId, String owner, int retryCount,
                                 LocalDateTime nextRetry, String error) {
        return jdbcTemplate.update("""
                UPDATE microservice_order_command
                   SET status=1,retry_count=?,next_retry_time=?,lease_owner=NULL,lease_until=NULL,
                       last_error=?,update_time=CURRENT_TIMESTAMP
                 WHERE command_id=? AND status=2 AND lease_owner=?
                """, retryCount, nextRetry, truncate(error), commandId, owner) == 1;
    }

    public boolean markDead(long commandId, Integer expectedStatus, String owner, String error) {
        String ownerPredicate = owner == null ? " AND lease_owner IS NULL" : " AND lease_owner=?";
        Object[] args = owner == null
                ? new Object[]{truncate(error), commandId, expectedStatus}
                : new Object[]{truncate(error), commandId, expectedStatus, owner};
        return jdbcTemplate.update("""
                UPDATE microservice_order_command
                   SET status=4,redis_reserved=0,lease_owner=NULL,lease_until=NULL,last_error=?,
                       dead_letter_time=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP
                 WHERE command_id=? AND status=?
                """ + ownerPredicate, args) == 1;
    }

    private RedisOrderCommand map(ResultSet rs, int rowNum) throws SQLException {
        return new RedisOrderCommand(rs.getLong("command_id"), rs.getLong("order_id"),
                rs.getLong("user_id"), rs.getBigDecimal("total_amount"),
                rs.getLong("timeout_seconds"), rs.getString("items_json"), rs.getInt("status"),
                rs.getBoolean("redis_reserved"), rs.getInt("retry_count"),
                rs.getObject("next_retry_time", LocalDateTime.class), rs.getString("lease_owner"),
                rs.getObject("lease_until", LocalDateTime.class), rs.getString("last_error"));
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_ERROR_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_ERROR_LENGTH);
    }
}
