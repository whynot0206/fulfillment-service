package com.why.fulfillment.commerce.cart.dto;

import java.math.BigDecimal;

/**
 * 购物车里的一行。
 *
 * <p>价格、名称、上架状态、库存都是**每次读购物车时现查的**，不是加购那一刻存下来的。
 * 购物车不是快照，它只记「选了哪个 SKU、几件」。真正的快照在下单时才产生。
 * 把价格写进 cart_item 会让用户看到几天前的旧价，而且下单时还得再查一次去比对。</p>
 *
 * @param skuId          SKU 编号
 * @param spuId          所属商品
 * @param name           商品名（现查）
 * @param specJson       规格（现查）
 * @param imageUrl       首图（现查）
 * @param price          当前售价（现查）
 * @param quantity       数量
 * @param selected       是否勾选结算
 * @param onSale         是否仍在售；下架商品仍留在车里，但不可结算
 * @param availableStock 可售库存快照，Inventory 不可达时为 null
 * @param subtotal       price * quantity，服务端算好避免前端浮点误差
 */
public record CartItemView(Long skuId,
                           Long spuId,
                           String name,
                           String specJson,
                           String imageUrl,
                           BigDecimal price,
                           Integer quantity,
                           boolean selected,
                           boolean onSale,
                           Integer availableStock,
                           BigDecimal subtotal) {
}
