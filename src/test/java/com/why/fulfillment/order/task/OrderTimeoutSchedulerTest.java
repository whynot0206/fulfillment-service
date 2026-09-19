package com.why.fulfillment.order.task;

import com.why.fulfillment.order.service.OrderExpirationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OrderTimeoutSchedulerTest {

    private static final String READY_QUEUE = "fulfillment:order-timeout";
    private static final String DEAD_LETTER_QUEUE = READY_QUEUE + ":dead-letter";

    private RedissonClient redissonClient;
    private RBlockingQueue<Long> readyQueue;
    private RBlockingQueue<Long> deadLetterQueue;
    private RDelayedQueue<Long> delayedQueue;
    private RAtomicLong retryCounter;
    private OrderExpirationService expirationService;

    @BeforeEach
    void setUp() {
        redissonClient = mock(RedissonClient.class);
        readyQueue = mock(RBlockingQueue.class);
        deadLetterQueue = mock(RBlockingQueue.class);
        delayedQueue = mock(RDelayedQueue.class);
        retryCounter = mock(RAtomicLong.class);
        expirationService = mock(OrderExpirationService.class);

        when(redissonClient.<Long>getBlockingQueue(READY_QUEUE)).thenReturn(readyQueue);
        when(redissonClient.<Long>getBlockingQueue(DEAD_LETTER_QUEUE)).thenReturn(deadLetterQueue);
        when(redissonClient.getDelayedQueue(readyQueue)).thenReturn(delayedQueue);
        when(redissonClient.getAtomicLong(anyString())).thenReturn(retryCounter);
    }

    @Test
    void successfulExpirationClearsRetryCounter() {
        when(readyQueue.poll()).thenReturn(101L, (Long) null);

        newScheduler(3, 2, 20).consumeExpiredOrders();

        verify(expirationService).expire(101L);
        verify(retryCounter).getAndDelete();
        verifyNoInteractions(delayedQueue, deadLetterQueue);
    }

    @Test
    void failedExpirationUsesExponentialBackoffAndRetainsRetryState() {
        when(readyQueue.poll()).thenReturn(102L, (Long) null, 102L, (Long) null);
        when(retryCounter.incrementAndGet()).thenReturn(1L, 2L);
        doThrow(new IllegalStateException("database unavailable"))
                .when(expirationService).expire(102L);
        OrderTimeoutScheduler scheduler = newScheduler(3, 2, 20);

        scheduler.consumeExpiredOrders();
        scheduler.consumeExpiredOrders();

        verify(delayedQueue).offer(102L, 2L, TimeUnit.SECONDS);
        verify(delayedQueue).offer(102L, 4L, TimeUnit.SECONDS);
        verify(deadLetterQueue, never()).offer(anyLong());
        verify(retryCounter, never()).getAndDelete();
    }

    @Test
    void failureAfterRetryLimitIsMovedToDeadLetterQueue() {
        when(readyQueue.poll()).thenReturn(103L, (Long) null, 103L, (Long) null);
        when(retryCounter.incrementAndGet()).thenReturn(1L, 2L);
        doThrow(new IllegalStateException("database unavailable"))
                .when(expirationService).expire(103L);
        OrderTimeoutScheduler scheduler = newScheduler(1, 2, 20);

        scheduler.consumeExpiredOrders();
        scheduler.consumeExpiredOrders();

        verify(delayedQueue).offer(103L, 2L, TimeUnit.SECONDS);
        verify(deadLetterQueue).offer(103L);
        verify(retryCounter).getAndDelete();
    }

    private OrderTimeoutScheduler newScheduler(int maxRetries,
                                               long initialDelaySeconds,
                                               long maxDelaySeconds) {
        return new OrderTimeoutScheduler(redissonClient, expirationService, 30_000L,
                maxRetries, initialDelaySeconds, maxDelaySeconds);
    }
}
