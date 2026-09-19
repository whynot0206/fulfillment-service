package com.why.fulfillment.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.why.fulfillment.order.entity.AsyncOrderCommand;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AsyncOrderCommandMapper extends BaseMapper<AsyncOrderCommand> {

    /** Insert once by order_id; a duplicate command is treated as an idempotent enqueue. */
    int insertIfAbsent(AsyncOrderCommand command);

    AsyncOrderCommand selectByOrderId(@Param("orderId") Long orderId);

    List<AsyncOrderCommand> listReady(@Param("limit") int limit);

    /** Claim a pending command or a command whose previous lease expired. */
    int claim(@Param("commandId") Long commandId,
              @Param("owner") String owner,
              @Param("leaseUntil") LocalDateTime leaseUntil);

    /** Completion is accepted only from the worker holding the current lease. */
    int markSucceeded(@Param("commandId") Long commandId,
                      @Param("owner") String owner);

    int scheduleRetry(@Param("commandId") Long commandId,
                      @Param("owner") String owner,
                      @Param("retryCount") int retryCount,
                      @Param("nextRetryTime") LocalDateTime nextRetryTime,
                      @Param("lastError") String lastError);

    int markDead(@Param("commandId") Long commandId,
                 @Param("owner") String owner,
                 @Param("lastError") String lastError,
                 @Param("deadLetterTime") LocalDateTime deadLetterTime);

    List<AsyncOrderCommand> listDead(@Param("limit") int limit);

    /** Commands whose Redis deduction has happened but whose MySQL transaction is not complete. */
    List<AsyncOrderCommand> listOutstandingForReconciliation();
}
