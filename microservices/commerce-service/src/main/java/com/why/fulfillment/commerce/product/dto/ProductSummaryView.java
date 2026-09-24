package com.why.fulfillment.commerce.product.dto;

import java.math.BigDecimal;

/**
 * 列表页的单个商品。
 *
 * @param spuId    SPU 编号
 * @param name     商品名
 * @param brand    品牌
 * @param coverUrl 首图，按 sort_order 取最小的一张，没有图时为 null
 * @param minPrice 起售价，即该 SPU 下所有上架 SKU 的最低价；无上架 SKU 时为 null
 */
public record ProductSummaryView(Long spuId,
                                 String name,
                                 String brand,
                                 String coverUrl,
                                 BigDecimal minPrice) {
}
