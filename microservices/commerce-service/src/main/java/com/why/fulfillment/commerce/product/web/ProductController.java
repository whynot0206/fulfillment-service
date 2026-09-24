package com.why.fulfillment.commerce.product.web;

import com.why.fulfillment.commerce.common.PageResult;
import com.why.fulfillment.commerce.product.dto.CategoryView;
import com.why.fulfillment.commerce.product.dto.ProductDetailView;
import com.why.fulfillment.commerce.product.dto.ProductSummaryView;
import com.why.fulfillment.commerce.product.dto.SkuView;
import com.why.fulfillment.commerce.product.service.ProductQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商品查询，匿名可访问。
 *
 * <p>Gateway 的鉴权白名单里必须包含 /api/products/**，否则未登录用户
 * 连首页都打不开。</p>
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductQueryService productQueryService;

    public ProductController(ProductQueryService productQueryService) {
        this.productQueryService = productQueryService;
    }

    @GetMapping
    public ResponseEntity<PageResult<ProductSummaryView>> list(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int size) {
        return ResponseEntity.ok(productQueryService.listProducts(categoryId, keyword, page, size));
    }

    @GetMapping("/categories")
    public ResponseEntity<List<CategoryView>> categories() {
        return ResponseEntity.ok(productQueryService.listCategories());
    }

    @GetMapping("/{spuId}")
    public ResponseEntity<ProductDetailView> detail(@PathVariable Long spuId) {
        return ResponseEntity.ok(productQueryService.getDetail(spuId));
    }

    @GetMapping("/skus/{skuId}")
    public ResponseEntity<SkuView> sku(@PathVariable Long skuId) {
        return ResponseEntity.ok(productQueryService.getSku(skuId));
    }
}
