package com.why.fulfillment.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.why.fulfillment.order.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    long countByStatus(@Param("status") int status);

    int cancelIfPending(@Param("orderId") Long orderId);

    int markPaidIfPending(@Param("orderId") Long orderId,
                          @Param("outTradeNo") String outTradeNo);
}
