package com.why.fulfillment.commerce.product.service;

import com.why.fulfillment.commerce.common.CommerceException;
import com.why.fulfillment.commerce.common.PageResult;
import com.why.fulfillment.commerce.product.dto.CategoryView;
import com.why.fulfillment.commerce.product.dto.ProductDetailView;
import com.why.fulfillment.commerce.product.dto.ProductSummaryView;
import com.why.fulfillment.commerce.product.dto.SkuView;
import com.why.fulfillment.commerce.product.entity.ProductImage;
import com.why.fulfillment.commerce.product.entity.ProductSku;
import com.why.fulfillment.commerce.product.entity.ProductSpu;
import com.why.fulfillment.commerce.product.mapper.ProductCategoryMapper;
import com.why.fulfillment.commerce.product.mapper.ProductImageMapper;
import com.why.fulfillment.commerce.product.mapper.ProductSkuMapper;
import com.why.fulfillment.commerce.product.mapper.ProductSpuMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 商品查询。纯读，无事务。 */
@Service
public class ProductQueryService {

    /** 上架。与 product_spu.status / product_sku.status 的取值一致。 */
    public static final int STATUS_LISTED = 1;

    private static final int MAX_PAGE_SIZE = 50;

    private final ProductSpuMapper spuMapper;
    private final ProductSkuMapper skuMapper;
    private final ProductImageMapper imageMapper;
    private final ProductCategoryMapper categoryMapper;
    private final SkuAvailabilityService availabilityService;

    public ProductQueryService(ProductSpuMapper spuMapper,
                               ProductSkuMapper skuMapper,
                               ProductImageMapper imageMapper,
                               ProductCategoryMapper categoryMapper,
                               SkuAvailabilityService availabilityService) {
        this.spuMapper = spuMapper;
        this.skuMapper = skuMapper;
        this.imageMapper = imageMapper;
        this.categoryMapper = categoryMapper;
        this.availabilityService = availabilityService;
    }

    /**
     * 商品列表。
     *
     * <p>不带库存：列表页做实时库存需要 Inventory 的批量查询接口，现在没有，
     * 逐个调用会让一页 20 个商品发 20+ 次 HTTP。见 {@link SkuAvailabilityService}。</p>
     */
    public PageResult<ProductSummaryView> listProducts(Long categoryId, String keyword, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        String safeKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();

        long total = spuMapper.countListed(categoryId, safeKeyword);
        if (total == 0) {
            return PageResult.empty(safePage, safeSize);
        }

        List<ProductSpu> spus = spuMapper.selectListedPage(
                categoryId, safeKeyword, (safePage - 1) * safeSize, safeSize);
        if (spus.isEmpty()) {
            return PageResult.of(List.of(), safePage, safeSize, total);
        }

        List<Long> spuIds = spus.stream().map(ProductSpu::getId).toList();
        Map<Long, String> covers = loadCovers(spuIds);
        Map<Long, BigDecimal> minPrices = loadMinPrices(spuIds);

        List<ProductSummaryView> items = new ArrayList<>(spus.size());
        for (ProductSpu spu : spus) {
            items.add(new ProductSummaryView(
                    spu.getId(),
                    spu.getName(),
                    spu.getBrand(),
                    covers.get(spu.getId()),
                    minPrices.get(spu.getId())));
        }
        return PageResult.of(items, safePage, safeSize, total);
    }

    /**
     * 商品详情。
     *
     * <p>下架 SPU 返回 404 而不是 200 带一个 status 字段：买家侧不需要知道
     * 「这个商品存在但下架了」，而且直接 404 能让前端少写一条分支。
     * 后台管理需要看下架商品时应该走另一条管理接口，不复用这个。</p>
     */
    public ProductDetailView getDetail(Long spuId) {
        ProductSpu spu = spuMapper.selectById(spuId);
        if (spu == null || !isListed(spu.getStatus())) {
            throw CommerceException.notFound("SPU_NOT_FOUND", "商品不存在或已下架");
        }

        List<String> images = imageMapper.selectBySpuId(spuId).stream()
                .map(ProductImage::getUrl)
                .toList();

        List<ProductSku> skus = skuMapper.selectListedBySpuId(spuId);
        List<Long> skuIds = skus.stream().map(ProductSku::getId).toList();
        Map<Long, Integer> availability = availabilityService.available(skuIds);

        List<SkuView> skuViews = new ArrayList<>(skus.size());
        for (ProductSku sku : skus) {
            skuViews.add(toSkuView(sku, availability.get(sku.getId())));
        }

        return new ProductDetailView(
                spu.getId(), spu.getName(), spu.getDescription(), spu.getBrand(),
                spu.getCategoryId(), images, skuViews);
    }

    /** 单个 SKU，带可售库存快照。用于加购前的前端校验。 */
    public SkuView getSku(Long skuId) {
        ProductSku sku = skuMapper.selectById(skuId);
        if (sku == null) {
            throw CommerceException.notFound("SKU_NOT_FOUND", "规格不存在");
        }
        return toSkuView(sku, availabilityService.available(skuId));
    }

    public List<CategoryView> listCategories() {
        return categoryMapper.selectVisible().stream()
                .map(category -> new CategoryView(category.getId(), category.getParentId(), category.getName()))
                .toList();
    }

    public static boolean isListed(Integer status) {
        return status != null && status == STATUS_LISTED;
    }

    private SkuView toSkuView(ProductSku sku, Integer availableStock) {
        return new SkuView(
                sku.getId(), sku.getSpuId(), sku.getSkuCode(), sku.getSpecJson(),
                sku.getPrice(), isListed(sku.getStatus()), availableStock);
    }

    private Map<Long, String> loadCovers(List<Long> spuIds) {
        Map<Long, String> covers = new HashMap<>();
        // Mapper 已按 (spu_id, sort_order, id) 排序，所以每个 spu 第一条就是首图。
        for (ProductImage image : imageMapper.selectBySpuIds(spuIds)) {
            covers.putIfAbsent(image.getSpuId(), image.getUrl());
        }
        return covers;
    }

    private Map<Long, BigDecimal> loadMinPrices(List<Long> spuIds) {
        Map<Long, BigDecimal> prices = new HashMap<>();
        for (ProductSkuMapper.SpuMinPrice row : skuMapper.selectMinPriceBySpuIds(spuIds)) {
            prices.put(row.spuId(), row.price());
        }
        return prices;
    }
}
