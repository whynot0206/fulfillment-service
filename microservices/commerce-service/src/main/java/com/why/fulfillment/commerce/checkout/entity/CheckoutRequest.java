package com.why.fulfillment.commerce.checkout.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一次结算请求的幂等记录。
 *
 * <p>状态含义：0 处理中、1 已提交、2 已拒绝。
 * 「处理中」是真实存在的一种结局——进程在调完订单服务之前崩了，这条记录就会停在 0，
 * 谁也不知道订单建没建成。把它记下来，比假装只有成功和失败两种结果要诚实。</p>
 */
public class CheckoutRequest {

    public static final int STATUS_IN_PROGRESS = 0;
    public static final int STATUS_SUBMITTED = 1;
    public static final int STATUS_REJECTED = 2;

    private Long id;
    private Long userId;
    private String idempotencyKey;
    private String requestDigest;
    private BigDecimal totalAmount;
    private Long orderId;
    private Integer status;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getRequestDigest() {
        return requestDigest;
    }

    public void setRequestDigest(String requestDigest) {
        this.requestDigest = requestDigest;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
