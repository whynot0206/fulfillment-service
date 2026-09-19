package com.why.fulfillment.order.entity;

import java.time.LocalDateTime;

public class OrderOutboxEvent {

    public static final String PAYMENT_CONFIRMED = "PAYMENT_CONFIRMED";
    public static final int PENDING = 0;
    public static final int PROCESSING = 1;
    public static final int SENT = 2;

    private Long eventId;
    private String eventType;
    private String bizKey;
    private String payload;
    private Integer status;
    private Integer retryCount;
    private LocalDateTime nextRetryTime;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public static OrderOutboxEvent paymentConfirmed(Long orderId, String outTradeNo) {
        OrderOutboxEvent event = new OrderOutboxEvent();
        event.eventType = PAYMENT_CONFIRMED;
        event.bizKey = String.valueOf(orderId);
        event.payload = String.valueOf(orderId);
        event.status = PENDING;
        event.retryCount = 0;
        event.nextRetryTime = LocalDateTime.now();
        return event;
    }

    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getBizKey() { return bizKey; }
    public void setBizKey(String bizKey) { this.bizKey = bizKey; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    public LocalDateTime getNextRetryTime() { return nextRetryTime; }
    public void setNextRetryTime(LocalDateTime nextRetryTime) { this.nextRetryTime = nextRetryTime; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
