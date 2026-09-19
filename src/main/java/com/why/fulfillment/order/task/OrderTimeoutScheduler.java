package com.why.fulfillment.order.task;

import com.why.fulfillment.order.event.OrderCreatedEvent;
import com.why.fulfillment.order.service.OrderExpirationService;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(
        name = {"fulfillment.scheduling.enabled", "fulfillment.order.timeout.enabled"},
        havingValue = "true",
        matchIfMissing = true)
public class OrderTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutScheduler.class);
    private static final String QUEUE_NAME = "fulfillment:order-timeout";
    private static final String DEAD_LETTER_QUEUE_NAME = QUEUE_NAME + ":dead-letter";
    private static final String RETRY_COUNTER_PREFIX = QUEUE_NAME + ":retry:";
    private static final int MAX_BATCH_SIZE = 100;

    private final RBlockingQueue<Long> readyQueue;
    private final RDelayedQueue<Long> delayedQueue;
    private final RBlockingQueue<Long> deadLetterQueue;
    private final RedissonClient redissonClient;
    private final OrderExpirationService expirationService;
    private final long defaultDelayMs;
    private final int maxRetries;
    private final long initialRetryDelaySeconds;
    private final long maxRetryDelaySeconds;

    /**
     * Keeps the original constructor available for callers that instantiate the scheduler directly.
     */
    public OrderTimeoutScheduler(RedissonClient redissonClient,
                                 OrderExpirationService expirationService,
                                 @Value("${fulfillment.order.timeout.delay-ms:1800000}") long defaultDelayMs) {
        this(redissonClient, expirationService, defaultDelayMs, 5, 5, 300);
    }

    @Autowired
    public OrderTimeoutScheduler(RedissonClient redissonClient,
                                 OrderExpirationService expirationService,
                                 @Value("${fulfillment.order.timeout.delay-ms:1800000}") long defaultDelayMs,
                                 @Value("${fulfillment.order.timeout.retry.max-retries:5}") int maxRetries,
                                 @Value("${fulfillment.order.timeout.retry.initial-delay-seconds:5}") long initialRetryDelaySeconds,
                                 @Value("${fulfillment.order.timeout.retry.max-delay-seconds:300}") long maxRetryDelaySeconds) {
        this.readyQueue = redissonClient.getBlockingQueue(QUEUE_NAME);
        this.delayedQueue = redissonClient.getDelayedQueue(readyQueue);
        this.deadLetterQueue = redissonClient.getBlockingQueue(DEAD_LETTER_QUEUE_NAME);
        this.redissonClient = redissonClient;
        this.expirationService = expirationService;
        this.defaultDelayMs = defaultDelayMs;
        this.maxRetries = Math.max(0, maxRetries);
        this.initialRetryDelaySeconds = Math.max(1, initialRetryDelaySeconds);
        this.maxRetryDelaySeconds = Math.max(this.initialRetryDelaySeconds, maxRetryDelaySeconds);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void scheduleAfterCommit(OrderCreatedEvent event) {
        long delayMs = event.timeout() == null ? defaultDelayMs : event.timeout().toMillis();
        delayedQueue.offer(event.orderId(), Math.max(1, delayMs), TimeUnit.MILLISECONDS);
    }

    @Scheduled(fixedDelayString = "${fulfillment.order.timeout.poll-ms:500}")
    public void consumeExpiredOrders() {
        for (int i = 0; i < MAX_BATCH_SIZE; i++) {
            Long orderId = readyQueue.poll();
            if (orderId == null) {
                return;
            }
            try {
                expirationService.expire(orderId);
                retryCounter(orderId).getAndDelete();
            } catch (RuntimeException exception) {
                scheduleRetryOrDeadLetter(orderId, exception);
            }
        }
    }

    private void scheduleRetryOrDeadLetter(long orderId, RuntimeException exception) {
        RAtomicLong counter = retryCounter(orderId);
        long retryCount = counter.incrementAndGet();
        String reason = failureReason(exception);
        if (retryCount > maxRetries) {
            deadLetterQueue.offer(orderId);
            counter.getAndDelete();
            log.error("Order timeout task moved to dead-letter queue: orderId={}, retries={}, reason={}",
                    orderId, retryCount, reason, exception);
            return;
        }

        long delaySeconds = retryDelaySeconds(retryCount);
        delayedQueue.offer(orderId, delaySeconds, TimeUnit.SECONDS);
        log.warn("Order timeout task failed; scheduled retry: orderId={}, retry={}, delaySeconds={}, reason={}",
                orderId, retryCount, delaySeconds, reason, exception);
    }

    private RAtomicLong retryCounter(long orderId) {
        return redissonClient.getAtomicLong(RETRY_COUNTER_PREFIX + orderId);
    }

    private long retryDelaySeconds(long retryCount) {
        long delay = initialRetryDelaySeconds;
        long doublings = Math.min(Math.max(0, retryCount - 1), 63);
        for (long i = 0; i < doublings && delay < maxRetryDelaySeconds; i++) {
            if (delay > maxRetryDelaySeconds / 2) {
                return maxRetryDelaySeconds;
            }
            delay *= 2;
        }
        return Math.min(delay, maxRetryDelaySeconds);
    }

    private String failureReason(RuntimeException exception) {
        if (exception.getMessage() != null && !exception.getMessage().isBlank()) {
            return exception.getMessage();
        }
        return exception.getClass().getSimpleName();
    }
}
