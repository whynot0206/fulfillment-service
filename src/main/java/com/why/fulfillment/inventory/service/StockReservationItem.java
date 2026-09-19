package com.why.fulfillment.inventory.service;

/** 下单时传入的最小库存预占契约，不暴露数据库实体。 */
public record StockReservationItem(Long skuId, Long spuId, int count) {
}
