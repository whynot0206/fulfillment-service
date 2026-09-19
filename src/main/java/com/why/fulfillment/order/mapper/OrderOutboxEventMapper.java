package com.why.fulfillment.order.mapper;

import com.why.fulfillment.order.entity.OrderOutboxEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OrderOutboxEventMapper {

    int insert(OrderOutboxEvent event);

    List<OrderOutboxEvent> listReady(@Param("limit") int limit);

    int claim(@Param("eventId") Long eventId);

    int markSent(@Param("eventId") Long eventId);

    int reschedule(@Param("eventId") Long eventId,
                   @Param("retryCount") int retryCount,
                   @Param("nextRetryTime") LocalDateTime nextRetryTime,
                   @Param("lastError") String lastError);
}
