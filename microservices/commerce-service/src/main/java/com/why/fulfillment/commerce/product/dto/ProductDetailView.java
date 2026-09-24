package com.why.fulfillment.commerce.product.dto;

import java.util.List;

/**
 * 详情页。
 *
 * @param spuId       SPU 编号
 * @param name        商品名
 * @param description 描述
 * @param brand       品牌
 * @param categoryId  分类
 * @param images      图片 URL，已按 sort_order 排好
 * @param skus        上架 SKU，含可售库存快照
 */
public record ProductDetailView(Long spuId,
                                String name,
                                String description,
                                String brand,
                                Long categoryId,
                                List<String> images,
                                List<SkuView> skus) {
}
