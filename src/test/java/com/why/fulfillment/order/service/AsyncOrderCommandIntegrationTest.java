package com.why.fulfillment.order.service;

import com.why.fulfillment.inventory.service.RedisInventoryService;
import com.why.fulfillment.inventory.service.StockReservationItem;
import com.why.fulfillment.order.entity.AsyncOrderCommand;
import com.why.fulfillment.order.entity.Order;
import com.why.fulfillment.order.mapper.AsyncOrderCommandMapper;
import com.why.fulfillment.order.task.AsyncOrderCommandProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The command path must be verified against MySQL because the lease and the
 * order/inventory transaction cannot be meaningfully covered by Mockito.
 */
@SpringBootTest(properties = {
        "fulfillment.scheduling.enabled=false",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl"
})
@DisplayName("异步订单命令真实 MySQL 持久化")
class AsyncOrderCommandIntegrationTest {

    private static final long ORDER_ID = 9_500_001L;
    private static final long FAILURE_ORDER_ID = 9_500_002L;
    private static final long USER_ID = 9_500_001L;
    private static final long SKU_ID = 1001L;

    @Autowired
    private AsyncOrderCommandService commandService;

    @Autowired
    private AsyncOrderCommandMapper commandMapper;

    @Autowired
    private AsyncOrderCommandProcessor processor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private RedissonClient redissonClient;

    @MockBean
    private RedisInventoryService redisInventoryService;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbcTemplate.update("""
                insert into sku_stock (sku_id, spu_id, stock, lock_stock, version)
                values (?, ?, 100, 0, 0)
                on duplicate key update stock = 100, lock_stock = 0, version = 0
                """, SKU_ID, 1L);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    @DisplayName("命令幂等入库，抢占后订单与命令完成在同一 MySQL 事务")
    void commandIsIdempotentAndPersistsPendingOrderAtomically() {
        Order order = pendingOrder();
        List<StockReservationItem> items = List.of(new StockReservationItem(SKU_ID, 1L, 2));

        AsyncOrderCommand first = commandService.enqueue(order, items, Duration.ofMinutes(5));
        AsyncOrderCommand duplicate = commandService.enqueue(order, items, Duration.ofMinutes(5));
        assertEquals(first.getCommandId(), duplicate.getCommandId());

        AsyncOrderCommand ready = commandMapper.selectByOrderId(ORDER_ID);
        assertNotNull(ready);
        assertEquals(AsyncOrderCommand.PENDING, ready.getStatus());
        assertEquals(1, commandMapper.claim(ready.getCommandId(), "mysql-test-worker",
                java.time.LocalDateTime.now().plusMinutes(1)));

        processor.processClaimed(ready, "mysql-test-worker");

        Map<String, Object> command = jdbcTemplate.queryForMap(
                "select status, retry_count, lease_owner from async_order_command where command_id = ?",
                first.getCommandId());
        assertEquals(AsyncOrderCommand.SUCCEEDED, ((Number) command.get("status")).intValue());
        assertEquals(0, ((Number) command.get("retry_count")).intValue());
        assertEquals(null, command.get("lease_owner"));

        Map<String, Object> persistedOrder = jdbcTemplate.queryForMap(
                "select user_id, total_amount, status from `order` where order_id = ?", ORDER_ID);
        assertEquals(USER_ID, ((Number) persistedOrder.get("user_id")).longValue());
        assertEquals(new BigDecimal("19.90"), persistedOrder.get("total_amount"));
        assertEquals(Order.PENDING_PAYMENT, ((Number) persistedOrder.get("status")).intValue());

        Integer lockCount = jdbcTemplate.queryForObject(
                "select count(*) from sku_stock_lock where order_id = ? and status = 1", Integer.class, ORDER_ID);
        assertEquals(1, lockCount);
        Integer stock = jdbcTemplate.queryForObject(
                "select stock from sku_stock where sku_id = ?", Integer.class, SKU_ID);
        assertEquals(98, stock);
    }

    @Test
    @DisplayName("库存业务失败时订单、锁定记录和命令完成状态一起回滚")
    void businessFailureRollsBackOrderAndInventoryWrites() {
        Order order = new Order();
        order.setOrderId(FAILURE_ORDER_ID);
        order.setUserId(USER_ID);
        order.setTotalAmount(new BigDecimal("9.90"));
        List<StockReservationItem> items = List.of(new StockReservationItem(SKU_ID, 1L, 101));

        AsyncOrderCommand command = commandService.enqueue(order, items, Duration.ofMinutes(5));
        AsyncOrderCommand ready = commandMapper.selectByOrderId(FAILURE_ORDER_ID);
        assertEquals(1, commandMapper.claim(command.getCommandId(), "mysql-test-worker-failure",
                java.time.LocalDateTime.now().plusMinutes(1)));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> processor.processClaimed(ready, "mysql-test-worker-failure"));

        Integer orderCount = jdbcTemplate.queryForObject(
                "select count(*) from `order` where order_id = ?", Integer.class, FAILURE_ORDER_ID);
        Integer lockCount = jdbcTemplate.queryForObject(
                "select count(*) from sku_stock_lock where order_id = ?", Integer.class, FAILURE_ORDER_ID);
        Integer stock = jdbcTemplate.queryForObject(
                "select stock from sku_stock where sku_id = ?", Integer.class, SKU_ID);
        AsyncOrderCommand afterFailure = commandMapper.selectByOrderId(FAILURE_ORDER_ID);

        assertEquals(0, orderCount);
        assertEquals(0, lockCount);
        assertEquals(100, stock);
        assertEquals(AsyncOrderCommand.PROCESSING, afterFailure.getStatus());
    }

    private Order pendingOrder() {
        Order order = new Order();
        order.setOrderId(ORDER_ID);
        order.setUserId(USER_ID);
        order.setTotalAmount(new BigDecimal("19.90"));
        return order;
    }

    private void cleanUp() {
        jdbcTemplate.update("delete from sku_stock_lock where order_id = ?", ORDER_ID);
        jdbcTemplate.update("delete from sku_stock_lock where order_id = ?", FAILURE_ORDER_ID);
        jdbcTemplate.update("delete from `order` where order_id = ?", ORDER_ID);
        jdbcTemplate.update("delete from `order` where order_id = ?", FAILURE_ORDER_ID);
        jdbcTemplate.update("delete from async_order_command where order_id = ?", ORDER_ID);
        jdbcTemplate.update("delete from async_order_command where order_id = ?", FAILURE_ORDER_ID);
    }
}
