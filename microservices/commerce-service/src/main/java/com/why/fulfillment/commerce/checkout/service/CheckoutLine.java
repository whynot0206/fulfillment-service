package com.why.fulfillment.commerce.checkout.service;

import java.math.BigDecimal;

/**
 * 一条通过了重新校验的结算明细——也就是价格快照本身。
 *
 * <p>这个记录一旦建好就不再改动，它是「用户同意的内容」的完整表达：
 * 买哪个 SKU、几件、单价多少、当时叫什么名字。往下游发的订单请求里的每个字段
 * 都只能来自这里，不能再回头去查商品表——中间只要再查一次，就有可能查到
 * 已经变过的价格，而那个价格用户没同意过。</p>
 *
 * @param skuId        SKU 编号，跨服务通用
 * @param spuId        所属 SPU
 * @param quantity     数量
 * @param price        单价，来自校验那一刻的商品表
 * @param nameSnapshot 商品名快照
 * @param specSnapshot 规格快照
 */
public record CheckoutLine(Long skuId, Long spuId, Integer quantity, BigDecimal price,
                           String nameSnapshot, String specSnapshot) {

    /** 该行小计。金额一律用 BigDecimal 相乘，不经过 double。 */
    public BigDecimal subtotal() {
        return price.multiply(BigDecimal.valueOf(quantity));
    }
}
