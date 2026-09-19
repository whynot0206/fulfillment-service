package com.why.fulfillment.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Redis 预扣成功后等待写入 MySQL 的订单命令。
 *
 * <p>命令自身是可重试的持久化消息：订单号提供幂等键，处理中状态由租约保护，
 * 超过重试上限后转为死信并由消费者补偿 Redis 库存。</p>
 */
@TableName("async_order_command")
public class AsyncOrderCommand {

    public static final int PENDING = 0;
    public static final int PROCESSING = 1;
    public static final int SUCCEEDED = 2;
    public static final int DEAD = 3;
    /** Readable aliases for callers that use command terminology. */
    public static final int COMPLETED = SUCCEEDED;
    public static final int DEAD_LETTER = DEAD;

    @TableId(type = IdType.AUTO)
    private Long commandId;
    private Long orderId;
    private Long userId;
    private BigDecimal totalAmount;
    private Long timeoutMs;
    private String itemsJson;
    private Integer status;
    private Integer retryCount;
    private LocalDateTime nextRetryTime;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private String lastError;
    private LocalDateTime deadLetterTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getCommandId() { return commandId; }
    public void setCommandId(Long commandId) { this.commandId = commandId; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public Long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(Long timeoutMs) { this.timeoutMs = timeoutMs; }
    public String getItemsJson() { return itemsJson; }
    public void setItemsJson(String itemsJson) { this.itemsJson = itemsJson; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    public LocalDateTime getNextRetryTime() { return nextRetryTime; }
    public void setNextRetryTime(LocalDateTime nextRetryTime) { this.nextRetryTime = nextRetryTime; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }
    public LocalDateTime getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(LocalDateTime leaseUntil) { this.leaseUntil = leaseUntil; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public LocalDateTime getDeadLetterTime() { return deadLetterTime; }
    public void setDeadLetterTime(LocalDateTime deadLetterTime) { this.deadLetterTime = deadLetterTime; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
