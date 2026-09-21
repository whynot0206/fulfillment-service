package com.why.fulfillment.inventory.service;

import com.why.fulfillment.api.inventory.InventoryQueryResponse;
import com.why.fulfillment.api.inventory.InventoryReserveItem;

import java.util.List;

public interface InventoryReservationService {

    void reserve(Long orderId, List<InventoryReserveItem> items);

    void release(Long orderId);

    void confirm(Long orderId);

    InventoryQueryResponse query(Long skuId);
}
