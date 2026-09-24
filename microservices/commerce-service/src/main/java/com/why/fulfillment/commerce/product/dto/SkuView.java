package com.why.fulfillment.commerce.product.dto;

import java.math.BigDecimal;

/**
 * 详情页 / 购物车里的单个 SKU。
 *
 * <p>{@code availableStock} 来自 inventory-service，是**查询那一刻**的快照，
 * 不构成任何可售承诺。真正的可售判定只发生在 Inventory 的原子条件更新里。
 * 前端可以用它做「仅剩 N 件」的提示，但不能用它替代下单校验。</p>
 *
 * <p>Inventory 不可达时该字段为 null，表示「查不到」，而不是 0。
 * 这两者含义完全不同：0 会让前端把在售商品显示成售罄。</p>
 *
 * @param skuId          SKU 编号，等于 Inventory 的 sku_id
 * @param spuId          所属 SPU
 * @param skuCode        商家编码
 * @param specJson       规格，原样透传 JSON 字符串由前端解析
 * @param price          当前售价
 * @param onSale         是否上架
 * @param availableStock 可售库存快照，查询失败为 null
 */
public record SkuView(Long skuId,
                      Long spuId,
                      String skuCode,
                      String specJson,
                      BigDecimal price,
                      boolean onSale,
                      Integer availableStock) {
}
