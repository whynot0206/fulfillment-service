package com.why.fulfillment.commerce.cart.web;

import com.why.fulfillment.commerce.auth.CurrentUser;
import com.why.fulfillment.commerce.auth.JwtPrincipal;
import com.why.fulfillment.commerce.cart.dto.AddToCartRequest;
import com.why.fulfillment.commerce.cart.dto.CartView;
import com.why.fulfillment.commerce.cart.dto.UpdateCartItemRequest;
import com.why.fulfillment.commerce.cart.service.CartService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 购物车，必须登录。
 *
 * <p>所有接口的用户身份都来自 {@link CurrentUser}（即令牌），路径里不出现 userId。
 * 一旦接口签名里有 userId 这个参数，就必须在每个方法里手写一遍「这是不是你自己」，
 * 漏一个就是越权。不给出这个参数，这类错误在语法层面就写不出来。</p>
 */
@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public ResponseEntity<CartView> view(@CurrentUser JwtPrincipal principal) {
        return ResponseEntity.ok(cartService.view(principal.userId()));
    }

    @PostMapping("/items")
    public ResponseEntity<CartView> add(@CurrentUser JwtPrincipal principal,
                                        @Valid @RequestBody AddToCartRequest request) {
        cartService.add(principal.userId(), request);
        return ResponseEntity.ok(cartService.view(principal.userId()));
    }

    @PutMapping("/items/{skuId}")
    public ResponseEntity<CartView> updateQuantity(@CurrentUser JwtPrincipal principal,
                                                   @PathVariable Long skuId,
                                                   @Valid @RequestBody UpdateCartItemRequest request) {
        cartService.updateQuantity(principal.userId(), skuId, request.quantity());
        return ResponseEntity.ok(cartService.view(principal.userId()));
    }

    @PutMapping("/items/{skuId}/selected")
    public ResponseEntity<CartView> updateSelected(@CurrentUser JwtPrincipal principal,
                                                   @PathVariable Long skuId,
                                                   @RequestParam boolean selected) {
        cartService.updateSelected(principal.userId(), skuId, selected);
        return ResponseEntity.ok(cartService.view(principal.userId()));
    }

    @DeleteMapping("/items/{skuId}")
    public ResponseEntity<CartView> remove(@CurrentUser JwtPrincipal principal,
                                           @PathVariable Long skuId) {
        cartService.remove(principal.userId(), skuId);
        return ResponseEntity.ok(cartService.view(principal.userId()));
    }
}
