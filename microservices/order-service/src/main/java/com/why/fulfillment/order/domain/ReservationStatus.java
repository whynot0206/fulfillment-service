package com.why.fulfillment.order.domain;

/**
 * 状态记录的是订单服务已知的预占事实，不能把远程调用异常误记成成功。
 */
public enum ReservationStatus {
    RESERVING(0),
    RESERVED(1),
    PENDING_COMPENSATION(2),
    COMPENSATED(3),
    FAILED(4);

    private final int code;

    ReservationStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static ReservationStatus fromCode(int code) {
        for (ReservationStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown reservation status " + code);
    }
}
