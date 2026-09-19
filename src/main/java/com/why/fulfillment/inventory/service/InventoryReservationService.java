package com.why.fulfillment.inventory.service;

import java.util.List;

public interface InventoryReservationService {

    void reserve(Long orderId, List<StockReservationItem> items);

    void release(Long orderId);

    void confirm(Long orderId);
}
