package com.why.fulfillment.order.domain;

import java.math.BigDecimal;

public record OrderItemRecord(Long skuId,
                              Long spuId,
                              Integer count,
                              BigDecimal price) {
}
