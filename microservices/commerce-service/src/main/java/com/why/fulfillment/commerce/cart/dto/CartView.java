package com.why.fulfillment.commerce.cart.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 整个购物车。
 *
 * @param items          所有条目，含已下架的
 * @param selectedCount  勾选且在售的条目数
 * @param selectedAmount 勾选且在售条目的合计金额；下架条目不计入
 */
public record CartView(List<CartItemView> items, int selectedCount, BigDecimal selectedAmount) {
}
