package com.why.fulfillment.order.service;

import com.why.fulfillment.order.entity.Order;
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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 支付回调幂等的真实 MySQL 并发验证。
 *
 * UPDATE 的条件裁决和 RR 隔离级别下的回退查询都必须由真实数据库验证，
 * Mockito 无法覆盖两个事务同时处理同一订单时的行锁等待和一致性读快照。
 */
@SpringBootTest(properties = {
        "fulfillment.scheduling.enabled=false",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl"
})
@DisplayName("支付回调并发幂等")
class OrderPaymentIdempotencyIntegrationTest {

    private static final long ORDER_ID = 9_100_000L;
    private static final long USER_ID = 9_100_000L;
    private static final String OUT_TRADE_NO = "payment-idempotency-test-9100000";

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private RedissonClient redissonClient;

    @BeforeEach
    void createPendingOrder() {
        cleanUp();
        jdbcTemplate.update("""
                        insert into `order`
                            (order_id, user_id, total_amount, status, out_trade_no, pay_time)
                        values (?, ?, ?, ?, null, null)
                        """,
                ORDER_ID, USER_ID, new BigDecimal("19.90"), Order.PENDING_PAYMENT);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from order_outbox_event where event_type = ? and biz_key = ?",
                "PAYMENT_CONFIRMED", String.valueOf(ORDER_ID));
        jdbcTemplate.update("delete from `order` where order_id = ?", ORDER_ID);
    }

    @Test
    @DisplayName("两个并发相同交易号回调均成功且只写一条库存确认事件")
    void concurrentSameTradeCallbacksAreIdempotent() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = pool.submit(() -> invokeCallback(start));
            Future<Boolean> second = pool.submit(() -> invokeCallback(start));
            start.countDown();

            assertTrue(first.get(30, TimeUnit.SECONDS));
            assertTrue(second.get(30, TimeUnit.SECONDS));

            Map<String, Object> order = jdbcTemplate.queryForMap(
                    "select status, out_trade_no from `order` where order_id = ?", ORDER_ID);
            assertEquals(Order.PAID, ((Number) order.get("status")).intValue());
            assertEquals(OUT_TRADE_NO, order.get("out_trade_no"));

            Integer outboxCount = jdbcTemplate.queryForObject("""
                            select count(*)
                            from order_outbox_event
                            where event_type = ? and biz_key = ?
                            """,
                    Integer.class, "PAYMENT_CONFIRMED", String.valueOf(ORDER_ID));
            assertEquals(1, outboxCount);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "支付回调测试线程未及时退出");
        }
    }

    private boolean invokeCallback(CountDownLatch start) throws InterruptedException {
        start.await(10, TimeUnit.SECONDS);
        return orderService.markPaid(ORDER_ID, OUT_TRADE_NO);
    }
}
