package com.why.fulfillment.commerce.product.dto;

/**
 * 分类。
 *
 * @param id       分类编号
 * @param parentId 父分类，0 表示一级
 * @param name     名称
 */
public record CategoryView(Long id, Long parentId, String name) {
}
