package com.why.fulfillment.inventory.redis;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class InventoryRedisReservationRepository {
    private final JdbcTemplate jdbcTemplate;

    public InventoryRedisReservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertIfAbsent(long orderId, String itemsJson, int status) {
        jdbcTemplate.update("""
                INSERT IGNORE INTO inventory_redis_reservation(order_id,items_json,status)
                VALUES (?,?,?)
                """, orderId, itemsJson, status);
    }

    public Optional<LedgerEntry> find(long orderId) {
        return jdbcTemplate.query("""
                SELECT order_id,items_json,status FROM inventory_redis_reservation WHERE order_id=?
                """, (rs, rowNum) -> new LedgerEntry(rs.getLong("order_id"),
                rs.getString("items_json"), rs.getInt("status")), orderId).stream().findFirst();
    }

    public boolean markMaterialized(long orderId, String itemsJson) {
        return jdbcTemplate.update("""
                UPDATE inventory_redis_reservation
                   SET status=2,update_time=CURRENT_TIMESTAMP
                 WHERE order_id=? AND items_json=? AND status=1
                """, orderId, itemsJson) == 1;
    }

    public boolean markCompensated(long orderId, String itemsJson) {
        return jdbcTemplate.update("""
                UPDATE inventory_redis_reservation
                   SET status=3,update_time=CURRENT_TIMESTAMP
                 WHERE order_id=? AND items_json=? AND status IN (1,2)
                """, orderId, itemsJson) == 1;
    }

    public List<LedgerEntry> findPending() {
        return jdbcTemplate.query("""
                SELECT order_id,items_json,status FROM inventory_redis_reservation
                 WHERE status=1 ORDER BY order_id
                """, (rs, rowNum) -> new LedgerEntry(rs.getLong("order_id"),
                rs.getString("items_json"), rs.getInt("status")));
    }

    public record LedgerEntry(long orderId, String itemsJson, int status) { }
}
