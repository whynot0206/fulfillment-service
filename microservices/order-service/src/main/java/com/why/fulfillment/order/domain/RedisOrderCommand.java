package com.why.fulfillment.order.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record RedisOrderCommand(Long commandId, Long orderId, Long userId,
                                BigDecimal totalAmount, Long timeoutSeconds,
                                String itemsJson, int status, boolean redisReserved,
                                int retryCount, LocalDateTime nextRetryTime,
                                String leaseOwner, LocalDateTime leaseUntil,
                                String lastError) {

    public static final int PREPARING = 0;
    public static final int READY = 1;
    public static final int PROCESSING = 2;
    public static final int SUCCEEDED = 3;
    public static final int DEAD = 4;
}
