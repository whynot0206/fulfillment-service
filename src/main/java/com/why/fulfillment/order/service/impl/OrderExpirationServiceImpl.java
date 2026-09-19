package com.why.fulfillment.order.service.impl;

import com.why.fulfillment.inventory.service.InventoryReservationService;
import com.why.fulfillment.order.mapper.OrderMapper;
import com.why.fulfillment.order.service.OrderExpirationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderExpirationServiceImpl implements OrderExpirationService {

    private final OrderMapper orderMapper;
    private final InventoryReservationService inventoryReservationService;

    public OrderExpirationServiceImpl(OrderMapper orderMapper,
                                      InventoryReservationService inventoryReservationService) {
        this.orderMapper = orderMapper;
        this.inventoryReservationService = inventoryReservationService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean expire(Long orderId) {
        // 只有待支付订单能被取消；支付回调先成功时这里返回 false，不会释放库存。
        if (orderMapper.cancelIfPending(orderId) != 1) {
            return false;
        }
        inventoryReservationService.release(orderId);
        return true;
    }
}
