package com.why.fulfillment.api.order;

import java.math.BigDecimal;

/**
 * One line of a Commerce-initiated order, carrying the price and product snapshot taken at
 * checkout time.
 *
 * <p>{@code price} is the authoritative number: Order never re-reads it from Commerce, so a
 * later price change cannot alter an existing order. {@code nameSnapshot} and
 * {@code specSnapshot} are display-only and may be null for orders created through the legacy
 * {@code POST /api/orders} entry point, which has no product context.</p>
 */
public record OrderCreateItem(Long skuId,
                              Long spuId,
                              Integer count,
                              BigDecimal price,
                              String nameSnapshot,
                              String specSnapshot) {
}
