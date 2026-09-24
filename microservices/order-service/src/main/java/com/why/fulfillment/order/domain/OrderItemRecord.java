package com.why.fulfillment.order.domain;

import java.math.BigDecimal;

/**
 * One order line.
 *
 * <p>{@code nameSnapshot} / {@code specSnapshot} are the product wording as it stood when the
 * order was placed. They are display-only and nullable: orders created through the public
 * {@code POST /api/orders} entry point have no product context, and rows written before the
 * snapshot columns existed have none either. Null means "not recorded", which the caller
 * should render as a fallback (the sku id), not as an empty product name.</p>
 *
 * <p>{@code price} is different in kind — it is not display data, it is the agreed unit price
 * and it participates in the idempotency payload comparison.</p>
 */
public record OrderItemRecord(Long skuId,
                              Long spuId,
                              Integer count,
                              BigDecimal price,
                              String nameSnapshot,
                              String specSnapshot) {

    /**
     * Snapshot-free line, for the public order API and for tests that do not care about
     * display wording. Kept so that adding the snapshot columns did not force an edit on every
     * existing call site — a mechanical edit across many files is exactly where a wrong
     * argument slips in unnoticed.
     */
    public OrderItemRecord(Long skuId, Long spuId, Integer count, BigDecimal price) {
        this(skuId, spuId, count, price, null, null);
    }
}
