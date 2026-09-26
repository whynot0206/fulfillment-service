package com.why.fulfillment.commerce.checkout.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一次结算请求的幂等记录。
 *
 * <p>状态含义：0 处理中、1 已提交、2 已拒绝。
 * 新记录在远程调用前原子保存订单号、完整快照和租约。处理中记录按租约重核对，
 * 超出创建窗口只允许只读确认；无法安全收敛时转人工，不等于订单确定失败。</p>
 */
public class CheckoutRequest {

    public static final int STATUS_IN_PROGRESS = 0;
    public static final int STATUS_SUBMITTED = 1;
    public static final int STATUS_REJECTED = 2;
    public static final int RECOVERY_ACTIVE = 0;
    public static final int RECOVERY_MANUAL = 1;

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
    private String requestPayload;
    private LocalDateTime recoveryDeadline;
    private Integer recoveryState;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private Integer attemptCount;
    private LocalDateTime nextRetryTime;
    private String resultState;
    private Boolean cartCleanupRequired;
    // Insert-only parameters; all deadlines are computed by the database clock.
    private long recoveryWindowSeconds;
    private long leaseSeconds;

    public String getRequestPayload() { return requestPayload; }
    public void setRequestPayload(String value) { requestPayload = value; }
    public LocalDateTime getRecoveryDeadline() { return recoveryDeadline; }
    public void setRecoveryDeadline(LocalDateTime value) { recoveryDeadline = value; }
    public Integer getRecoveryState() { return recoveryState; }
    public void setRecoveryState(Integer value) { recoveryState = value; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String value) { leaseOwner = value; }
    public LocalDateTime getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(LocalDateTime value) { leaseUntil = value; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer value) { attemptCount = value; }
    public LocalDateTime getNextRetryTime() { return nextRetryTime; }
    public void setNextRetryTime(LocalDateTime value) { nextRetryTime = value; }
    public String getResultState() { return resultState; }
    public void setResultState(String value) { resultState = value; }
    public Boolean getCartCleanupRequired() { return cartCleanupRequired; }
    public void setCartCleanupRequired(Boolean value) { cartCleanupRequired = value; }
    public long getRecoveryWindowSeconds() { return recoveryWindowSeconds; }
    public void setRecoveryWindowSeconds(long value) { recoveryWindowSeconds = value; }
    public long getLeaseSeconds() { return leaseSeconds; }
    public void setLeaseSeconds(long value) { leaseSeconds = value; }

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
