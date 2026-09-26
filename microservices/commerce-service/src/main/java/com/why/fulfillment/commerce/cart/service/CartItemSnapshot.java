package com.why.fulfillment.commerce.cart.service;

/**
 * 缓存里存的购物车条目——只有购物车自己的事实，没有任何商品或库存字段。
 *
 * <p>这个取舍是整个缓存设计的核心：如果把价格、名称、库存一起缓存，
 * 就得在「改价」「改名」「下架」「库存变动」四件事上都做失效，
 * 而其中库存每秒都在变，等于缓存永远是脏的。只缓存购物车事实的话，
 * 失效时机只有一个——这个用户动了自己的购物车。</p>
 *
 * @param skuId    SKU 编号
 * @param quantity 数量
 * @param selected 是否勾选
 * @param rowId    原购物车行身份，删除后重加会获得不同身份
 * @param revision 原购物车行版本；任何写操作都会递增，避免修改后恢复原值的 ABA
 */
public record CartItemSnapshot(Long skuId, Integer quantity, Boolean selected, Long rowId, Long revision) {
    /** Old cache/test values remain readable but are never sufficient for automatic cleanup. */
    public CartItemSnapshot(Long skuId, Integer quantity, Boolean selected) {
        this(skuId, quantity, selected, null, null);
    }
}
