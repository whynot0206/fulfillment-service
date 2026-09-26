package com.why.fulfillment.commerce.cart.service;

import com.why.fulfillment.commerce.cart.dto.AddToCartRequest;
import com.why.fulfillment.commerce.cart.dto.CartItemView;
import com.why.fulfillment.commerce.cart.dto.CartView;
import com.why.fulfillment.commerce.cart.entity.CartItem;
import com.why.fulfillment.commerce.cart.mapper.CartItemMapper;
import com.why.fulfillment.commerce.common.CommerceException;
import com.why.fulfillment.commerce.product.entity.ProductImage;
import com.why.fulfillment.commerce.product.entity.ProductSku;
import com.why.fulfillment.commerce.product.entity.ProductSpu;
import com.why.fulfillment.commerce.product.mapper.ProductImageMapper;
import com.why.fulfillment.commerce.product.mapper.ProductSkuMapper;
import com.why.fulfillment.commerce.product.mapper.ProductSpuMapper;
import com.why.fulfillment.commerce.product.service.ProductQueryService;
import com.why.fulfillment.commerce.product.service.SkuAvailabilityService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 购物车读写。事务边界在这一层。 */
@Service
public class CartService {

    private final CartItemMapper cartItemMapper;
    private final CartCache cartCache;
    private final ProductSkuMapper skuMapper;
    private final ProductSpuMapper spuMapper;
    private final ProductImageMapper imageMapper;
    private final SkuAvailabilityService availabilityService;
    private final int maxItems;
    private final int maxQuantityPerSku;

    public CartService(CartItemMapper cartItemMapper,
                       CartCache cartCache,
                       ProductSkuMapper skuMapper,
                       ProductSpuMapper spuMapper,
                       ProductImageMapper imageMapper,
                       SkuAvailabilityService availabilityService,
                       @Value("${commerce.cart.max-items}") int maxItems,
                       @Value("${commerce.cart.max-quantity-per-sku}") int maxQuantityPerSku) {
        this.cartItemMapper = cartItemMapper;
        this.cartCache = cartCache;
        this.skuMapper = skuMapper;
        this.spuMapper = spuMapper;
        this.imageMapper = imageMapper;
        this.availabilityService = availabilityService;
        this.maxItems = maxItems;
        this.maxQuantityPerSku = maxQuantityPerSku;
    }

    /**
     * 读购物车：先看缓存，未命中回源并回填。
     *
     * <p>缓存里只有 skuId/数量/勾选；商品信息和库存每次都现查，见
     * {@link CartItemSnapshot} 的说明。</p>
     */
    public CartView view(long userId) {
        List<CartItemSnapshot> snapshots = cartCache.read(userId);
        if (snapshots == null) {
            snapshots = loadFromDatabase(userId);
            cartCache.write(userId, snapshots);
        }
        return assemble(snapshots);
    }

    /** 结算入口需要的原始条目，不带商品信息。 */
    public List<CartItemSnapshot> selectedItems(long userId) {
        return loadFromDatabase(userId).stream()
                .filter(item -> Boolean.TRUE.equals(item.selected()))
                .toList();
    }

    @Transactional
    public void add(long userId, AddToCartRequest request) {
        ProductSku sku = skuMapper.selectById(request.skuId());
        if (sku == null) {
            throw CommerceException.notFound("SKU_NOT_FOUND", "规格不存在");
        }
        if (!ProductQueryService.isListed(sku.getStatus())) {
            throw CommerceException.badRequest("SKU_NOT_ON_SALE", "该规格已下架");
        }

        // 条目数上限：只在「这个 SKU 还不在车里」时才算，否则累加已有条目会被误拦。
        if (cartItemMapper.selectByUserAndSku(userId, request.skuId()) == null
                && cartItemMapper.countByUserId(userId) >= maxItems) {
            throw CommerceException.badRequest("CART_FULL", "购物车最多放 " + maxItems + " 种商品");
        }

        cartItemMapper.upsertAccumulate(userId, request.skuId(), request.quantity(), maxQuantityPerSku);
        cartCache.evictAfterTransaction(userId);
    }

    @Transactional
    public void updateQuantity(long userId, long skuId, int quantity) {
        if (quantity > maxQuantityPerSku) {
            throw CommerceException.badRequest("QUANTITY_TOO_LARGE",
                    "单个规格最多 " + maxQuantityPerSku + " 件");
        }
        // WHERE 里带 user_id，影响行数为 0 即代表「不是你的条目」或「条目不存在」。
        // 两者都回 404，不告诉调用方「这条存在但不属于你」。
        requireAffected(cartItemMapper.updateQuantity(userId, skuId, quantity));
        cartCache.evictAfterTransaction(userId);
    }

    @Transactional
    public void updateSelected(long userId, long skuId, boolean selected) {
        requireAffected(cartItemMapper.updateSelected(userId, skuId, selected));
        cartCache.evictAfterTransaction(userId);
    }

    @Transactional
    public void remove(long userId, long skuId) {
        requireAffected(cartItemMapper.delete(userId, skuId));
        cartCache.evictAfterTransaction(userId);
    }

    /**
     * Clear only immutable checkout snapshots. Partial matches remove only untouched rows;
     * false means the caller must preserve its cleanup-required notice, not retry with weaker predicates.
     * Database failure propagates to the checkout caller, which must not reinterpret it as order failure.
     */
    @Transactional
    public boolean removeCheckedOutSnapshot(long userId, List<CartItemSnapshot> snapshots) {
        if (snapshots == null) {
            return false;
        }
        if (snapshots.isEmpty()) {
            return true;
        }
        if (snapshots.stream().anyMatch(snapshot -> snapshot == null
                || snapshot.rowId() == null || snapshot.rowId() <= 0
                || snapshot.revision() == null || snapshot.revision() < 0
                || snapshot.skuId() == null || !Boolean.TRUE.equals(snapshot.selected()))
                || snapshots.stream().map(CartItemSnapshot::rowId).distinct().count() != snapshots.size()) {
            return false;
        }
        int removed = cartItemMapper.deleteUnchangedSnapshots(userId, snapshots);
        cartCache.evictAfterTransaction(userId);
        return removed == snapshots.size();
    }

    private List<CartItemSnapshot> loadFromDatabase(long userId) {
        List<CartItem> rows = cartItemMapper.selectByUserId(userId);
        List<CartItemSnapshot> snapshots = new ArrayList<>(rows.size());
        for (CartItem row : rows) {
            snapshots.add(new CartItemSnapshot(row.getSkuId(), row.getQuantity(), row.getSelected(),
                    row.getId(), row.getRevision()));
        }
        return snapshots;
    }

    private CartView assemble(List<CartItemSnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return new CartView(List.of(), 0, BigDecimal.ZERO);
        }

        List<Long> skuIds = snapshots.stream().map(CartItemSnapshot::skuId).toList();
        Map<Long, ProductSku> skus = new HashMap<>();
        for (ProductSku sku : skuMapper.selectByIds(skuIds)) {
            skus.put(sku.getId(), sku);
        }
        Map<Long, Integer> availability = availabilityService.available(skuIds);

        List<Long> spuIds = skus.values().stream().map(ProductSku::getSpuId).distinct().toList();
        Map<Long, ProductSpu> spus = new HashMap<>();
        for (Long spuId : spuIds) {
            ProductSpu spu = spuMapper.selectById(spuId);
            if (spu != null) {
                spus.put(spuId, spu);
            }
        }
        Map<Long, String> covers = new HashMap<>();
        if (!spuIds.isEmpty()) {
            for (ProductImage image : imageMapper.selectBySpuIds(spuIds)) {
                covers.putIfAbsent(image.getSpuId(), image.getUrl());
            }
        }

        List<CartItemView> items = new ArrayList<>(snapshots.size());
        int selectedCount = 0;
        BigDecimal selectedAmount = BigDecimal.ZERO;

        for (CartItemSnapshot snapshot : snapshots) {
            ProductSku sku = skus.get(snapshot.skuId());
            if (sku == null) {
                // SKU 被彻底删了（不是下架）。跳过而不是报错——一条脏数据不该让整车打不开。
                continue;
            }
            ProductSpu spu = spus.get(sku.getSpuId());
            boolean onSale = ProductQueryService.isListed(sku.getStatus())
                    && spu != null && ProductQueryService.isListed(spu.getStatus());
            boolean selected = Boolean.TRUE.equals(snapshot.selected());
            BigDecimal subtotal = sku.getPrice().multiply(BigDecimal.valueOf(snapshot.quantity()));

            items.add(new CartItemView(
                    sku.getId(), sku.getSpuId(),
                    spu == null ? "" : spu.getName(),
                    sku.getSpecJson(),
                    covers.get(sku.getSpuId()),
                    sku.getPrice(), snapshot.quantity(), selected, onSale,
                    availability.get(sku.getId()), subtotal));

            // 下架商品即使还勾着也不计入合计，否则前端显示的总价会包含结算时必然被拒的条目。
            if (selected && onSale) {
                selectedCount++;
                selectedAmount = selectedAmount.add(subtotal);
            }
        }
        return new CartView(items, selectedCount, selectedAmount);
    }

    private static void requireAffected(int affected) {
        if (affected == 0) {
            throw CommerceException.notFound("CART_ITEM_NOT_FOUND", "购物车里没有这个条目");
        }
    }
}
