package com.why.fulfillment.commerce.checkout.service;

import com.why.fulfillment.api.order.OrderClient;
import com.why.fulfillment.api.order.OrderCreateRequest;
import com.why.fulfillment.api.order.OrderCreateResponse;
import com.why.fulfillment.commerce.cart.service.CartItemSnapshot;
import com.why.fulfillment.commerce.cart.service.CartService;
import com.why.fulfillment.commerce.checkout.dto.CheckoutResultView;
import com.why.fulfillment.commerce.checkout.dto.CheckoutSubmitRequest;
import com.why.fulfillment.commerce.checkout.entity.CheckoutRequest;
import com.why.fulfillment.commerce.checkout.mapper.CheckoutRequestMapper;
import com.why.fulfillment.commerce.common.CommerceException;
import com.why.fulfillment.commerce.product.entity.ProductSku;
import com.why.fulfillment.commerce.product.entity.ProductSpu;
import com.why.fulfillment.commerce.product.mapper.ProductSkuMapper;
import com.why.fulfillment.commerce.product.mapper.ProductSpuMapper;
import com.why.fulfillment.commerce.product.service.SkuAvailabilityService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 结算的失败用例。
 *
 * <p><b>这个文件现在是红的，而且必须先红。</b>它钉住的是 {@code CheckoutService}
 * 里留空的三个方法（{@code revalidate} / {@code digest} / {@code claimIdempotencyKey}）
 * 应该有的行为。跑一次会看到一片 {@code UnsupportedOperationException}，
 * 那就是你的待办清单。</p>
 *
 * <p>建议的推进顺序：先让 {@code revalidate} 那一组绿，再是 {@code digest}，
 * 最后是 {@code claimIdempotencyKey}——最后这组是唯一涉及并发的，
 * 前两组绿了之后你才能确定失败原因只可能出在它身上。</p>
 *
 * <p>这里<b>没有用 {@code MockitoExtension}</b>，是为了不触发严格存根检查：
 * 你的实现可以自由选择调 {@code available(Long)} 还是 {@code available(Collection)}，
 * 两个都被存根了，用哪个都不会因为「有未使用的存根」而误报失败。
 * 测试该约束的是行为，不是你按什么顺序调了哪个方法。</p>
 */
class CheckoutServiceTest {

    private static final long USER_ID = 9001L;
    private static final long SKU_A = 1001L;
    private static final long SKU_B = 1002L;
    private static final long SPU_A = 2001L;
    private static final long SPU_B = 2002L;
    private static final String KEY = "11111111-2222-3333-4444-555555555555";

    private final CartService cartService = mock(CartService.class);
    private final ProductSkuMapper skuMapper = mock(ProductSkuMapper.class);
    private final ProductSpuMapper spuMapper = mock(ProductSpuMapper.class);
    private final SkuAvailabilityService availabilityService = mock(SkuAvailabilityService.class);
    private final CheckoutRequestMapper checkoutRequestMapper = mock(CheckoutRequestMapper.class);
    private final OrderClient orderClient = mock(OrderClient.class);

    private final CheckoutService checkoutService = new CheckoutService(
            cartService, skuMapper, spuMapper, availabilityService,
            checkoutRequestMapper, orderClient, new OrderIdGenerator(0L), 1800L);

    // =================================================================================
    // 第一组：revalidate —— 重新校验与价格快照
    // =================================================================================

    /**
     * SKU 被硬删了。购物车里还留着这一行，但商品表里已经查不到。
     *
     * <p>这不是 400 而是 404：用户没做错什么，是引用的东西不在了。</p>
     */
    @Test
    void missingSkuIsRejectedAsNotFound() {
        when(skuMapper.selectByIds(anyList())).thenReturn(List.of());

        assertThatThrownBy(() -> checkoutService.revalidate(USER_ID, List.of(cartItem(SKU_A, 1))))
                .isInstanceOf(CommerceException.class)
                .satisfies(thrown -> {
                    CommerceException exception = (CommerceException) thrown;
                    assertThat(exception.getCode()).isEqualTo("SKU_NOT_FOUND");
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }

    /** SKU 自己下架了。错误消息里必须出现商品名，否则用户不知道该删哪一件。 */
    @Test
    void delistedSkuIsRejectedAndNamed() {
        givenCatalog(sku(SKU_A, SPU_A, "9.99", 0), spu(SPU_A, "机械键盘", 1));
        givenAvailability(Map.of(SKU_A, 100));

        assertThatThrownBy(() -> checkoutService.revalidate(USER_ID, List.of(cartItem(SKU_A, 1))))
                .isInstanceOf(CommerceException.class)
                .satisfies(thrown -> {
                    CommerceException exception = (CommerceException) thrown;
                    assertThat(exception.getCode()).isEqualTo("SKU_NOT_ON_SALE");
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                })
                .hasMessageContaining("机械键盘");
    }

    /**
     * SPU 下架但 SKU 还是上架状态——这是最容易漏的一种。
     *
     * <p>下架整个商品时通常只改 SPU 的状态，底下的 SKU 行没人去改。
     * 只查 SKU 状态的实现会让这批商品照常卖出去。</p>
     */
    @Test
    void delistedSpuIsRejectedEvenWhenTheSkuStillLooksListed() {
        givenCatalog(sku(SKU_A, SPU_A, "9.99", 1), spu(SPU_A, "机械键盘", 0));
        givenAvailability(Map.of(SKU_A, 100));

        assertThatThrownBy(() -> checkoutService.revalidate(USER_ID, List.of(cartItem(SKU_A, 1))))
                .isInstanceOf(CommerceException.class)
                .satisfies(thrown -> assertThat(((CommerceException) thrown).getCode())
                        .isEqualTo("SKU_NOT_ON_SALE"));
    }

    /**
     * 数量非法。
     *
     * <p>数据库上有 {@code CHECK (quantity > 0)}，所以正常路径走不到这里。
     * 这条用例存在是因为「上游已经校验过了」不能作为不校验的理由——
     * 上游可能是另一个团队写的，也可能是明天的你直接写库补数据。</p>
     */
    @Test
    void nonPositiveQuantityIsRejected() {
        givenCatalog(sku(SKU_A, SPU_A, "9.99", 1), spu(SPU_A, "机械键盘", 1));
        givenAvailability(Map.of(SKU_A, 100));

        assertThatThrownBy(() -> checkoutService.revalidate(USER_ID, List.of(cartItem(SKU_A, 0))))
                .isInstanceOf(CommerceException.class)
                .satisfies(thrown -> assertThat(((CommerceException) thrown).getCode())
                        .isEqualTo("INVALID_QUANTITY"));
    }

    /** 可售库存明显不够，提前拦掉——省一次跨服务往返，也给用户一个更具体的提示。 */
    @Test
    void obviouslyInsufficientStockIsRejectedBeforeCallingOrderService() {
        givenCatalog(sku(SKU_A, SPU_A, "9.99", 1), spu(SPU_A, "机械键盘", 1));
        givenAvailability(Map.of(SKU_A, 1));

        assertThatThrownBy(() -> checkoutService.revalidate(USER_ID, List.of(cartItem(SKU_A, 2))))
                .isInstanceOf(CommerceException.class)
                .satisfies(thrown -> assertThat(((CommerceException) thrown).getCode())
                        .isEqualTo("INSUFFICIENT_STOCK"));
    }

    /**
     * <b>这一条是这组里最重要的。</b>
     *
     * <p>库存服务不可达时 {@code available} 返回 null，含义是「不知道」，不是「没有」。
     * 把 null 当 0 处理的实现，会在 Inventory 抖动的那几秒里拒绝掉<b>所有</b>下单——
     * 一个只读依赖的短暂故障被放大成了整站不能下单。</p>
     *
     * <p>正确做法是放行，把裁决权交给 Inventory 的原子预占。最坏情况是用户下单后
     * 被告知库存不足，那本来就是可能发生的（这里查到的库存不构成任何保证）。</p>
     */
    @Test
    void unknownStockIsNotTreatedAsZero() {
        givenCatalog(sku(SKU_A, SPU_A, "9.99", 1), spu(SPU_A, "机械键盘", 1));
        givenAvailability(singletonMapWithNull(SKU_A));

        List<CheckoutLine> lines = checkoutService.revalidate(USER_ID, List.of(cartItem(SKU_A, 2)));

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).quantity()).isEqualTo(2);
    }

    /** 快照必须取自这一刻查到的商品数据，而不是购物车里的任何东西。 */
    @Test
    void snapshotCarriesThePriceAndNameFoundAtRevalidation() {
        givenCatalog(sku(SKU_A, SPU_A, "19.90", 1), spu(SPU_A, "机械键盘", 1));
        givenAvailability(Map.of(SKU_A, 100));

        List<CheckoutLine> lines = checkoutService.revalidate(USER_ID, List.of(cartItem(SKU_A, 3)));

        assertThat(lines).hasSize(1);
        CheckoutLine line = lines.get(0);
        assertThat(line.skuId()).isEqualTo(SKU_A);
        assertThat(line.spuId()).isEqualTo(SPU_A);
        assertThat(line.quantity()).isEqualTo(3);
        assertThat(line.price()).isEqualByComparingTo("19.90");
        assertThat(line.nameSnapshot()).isEqualTo("机械键盘");
        assertThat(line.specSnapshot()).isEqualTo("{\"色\":\"黑\"}");
        assertThat(line.subtotal()).isEqualByComparingTo("59.70");
    }

    /**
     * 多条明细不能退化成 N+1。
     *
     * <p>两个 SKU 属于两个 SPU，允许查两次 SPU（或一次批量），
     * 但 SKU 只能查一次——{@code selectById} 一件件查在购物车有 50 行时就是 50 次往返。</p>
     */
    @Test
    void multipleLinesAreLoadedWithoutQueryingSkusOneByOne() {
        when(skuMapper.selectByIds(anyList()))
                .thenReturn(List.of(sku(SKU_A, SPU_A, "10.00", 1), sku(SKU_B, SPU_B, "5.50", 1)));
        when(spuMapper.selectById(SPU_A)).thenReturn(spu(SPU_A, "机械键盘", 1));
        when(spuMapper.selectById(SPU_B)).thenReturn(spu(SPU_B, "鼠标垫", 1));
        givenAvailability(Map.of(SKU_A, 100, SKU_B, 100));

        List<CheckoutLine> lines = checkoutService.revalidate(USER_ID,
                List.of(cartItem(SKU_A, 1), cartItem(SKU_B, 2)));

        assertThat(CheckoutService.sum(lines)).isEqualByComparingTo("21.00");
        verify(skuMapper, never()).selectById(anyLong());
    }

    // =================================================================================
    // 第二组：digest —— 请求摘要
    // =================================================================================

    /** 列定义是 CHAR(64)，长度或大小写不对会在写库时炸。 */
    @Test
    void digestIsSixtyFourLowercaseHexCharacters() {
        String digest = checkoutService.digest(USER_ID, List.of(line(SKU_A, 1, "9.99")),
                new BigDecimal("9.99"));

        assertThat(digest).matches("[0-9a-f]{64}");
    }

    /**
     * 顺序不敏感。
     *
     * <p>前端换一次遍历顺序（比如从 Map 改成数组），同一份购物车就会算出不同摘要，
     * 重试立刻被判成「同键异载荷」冲突。摘要前必须先规范化。</p>
     */
    @Test
    void digestIgnoresLineOrder() {
        List<CheckoutLine> forward = List.of(line(SKU_A, 1, "9.99"), line(SKU_B, 2, "5.50"));
        List<CheckoutLine> reversed = List.of(line(SKU_B, 2, "5.50"), line(SKU_A, 1, "9.99"));
        BigDecimal total = new BigDecimal("20.99");

        assertThat(checkoutService.digest(USER_ID, forward, total))
                .isEqualTo(checkoutService.digest(USER_ID, reversed, total));
    }

    /**
     * 标度不敏感：5 和 5.00 是同一笔钱。
     *
     * <p>不做 setScale 的话，同一个价格从不同路径取出来（JDBC 给 5.00、
     * 代码里写 5）会算出不同摘要。</p>
     */
    @Test
    void digestIgnoresTrailingZeroesInMoney() {
        assertThat(checkoutService.digest(USER_ID, List.of(line(SKU_A, 1, "5")), new BigDecimal("5")))
                .isEqualTo(checkoutService.digest(USER_ID, List.of(line(SKU_A, 1, "5.00")),
                        new BigDecimal("5.00")));
    }

    /** 数量变了就是另一笔结算。 */
    @Test
    void digestChangesWhenQuantityChanges() {
        assertThat(checkoutService.digest(USER_ID, List.of(line(SKU_A, 1, "9.99")), new BigDecimal("9.99")))
                .isNotEqualTo(checkoutService.digest(USER_ID, List.of(line(SKU_A, 2, "9.99")),
                        new BigDecimal("19.98")));
    }

    /** 价格变了也是另一笔结算——即便总额碰巧一样，也不该判成重放。 */
    @Test
    void digestChangesWhenPriceChanges() {
        assertThat(checkoutService.digest(USER_ID, List.of(line(SKU_A, 1, "9.99")), new BigDecimal("9.99")))
                .isNotEqualTo(checkoutService.digest(USER_ID, List.of(line(SKU_A, 1, "8.99")),
                        new BigDecimal("8.99")));
    }

    /**
     * 不同用户必须算出不同摘要。
     *
     * <p>唯一键是 (user_id, idempotency_key)，摘要只在同一个用户内部比较，
     * 所以理论上 userId 不进摘要也不会错。但两个用户凑巧用了同一个键、
     * 内容也一样时，两条记录的 digest 会完全相同，排查时无从分辨。
     * 把 userId 摘进去的成本是零。</p>
     */
    @Test
    void digestIsScopedToTheUser() {
        List<CheckoutLine> lines = List.of(line(SKU_A, 1, "9.99"));
        BigDecimal total = new BigDecimal("9.99");

        assertThat(checkoutService.digest(USER_ID, lines, total))
                .isNotEqualTo(checkoutService.digest(USER_ID + 1, lines, total));
    }

    /**
     * 字段拼接必须有不会出现在内容里的分隔符。
     *
     * <p>两组明显不同的数据：{@code (skuId=1, 数量=11)} 和 {@code (skuId=11, 数量=1)}。
     * 直接把数字首尾相接会得到同一个串 "111"，于是两笔不同的结算共享一个摘要，
     * 第二笔被当成第一笔的重放——用户以为下单了，其实没有。</p>
     */
    @Test
    void digestSeparatesFieldsUnambiguously() {
        String a = checkoutService.digest(USER_ID, List.of(line(1L, 11, "1.00")), new BigDecimal("11.00"));
        String b = checkoutService.digest(USER_ID, List.of(line(11L, 1, "1.00")), new BigDecimal("1.00"));

        assertThat(a).isNotEqualTo(b);
    }

    // =================================================================================
    // 第三组：claimIdempotencyKey —— 幂等键认领
    // =================================================================================

    /**
     * 键是新的：插入成功，拿回自增主键。
     *
     * <p>顺带钉死「先插后查」：happy path 上<b>一次查询都不能有</b>。
     * 如果这里 verify 失败，说明写成了「先查有没有」——那就存在两个请求
     * 同时查到「没有」的窗口，唯一键会在其中一个上炸成 500。</p>
     */
    @Test
    void aFreshKeyIsClaimedWithoutQueryingFirst() {
        whenInsertSucceedsWithId(77L);

        IdempotencyClaim claim = checkoutService.claimIdempotencyKey(USER_ID, KEY, "digest-a");

        assertThat(claim.outcome()).isEqualTo(IdempotencyClaim.Outcome.CLAIMED);
        assertThat(claim.claimId()).isEqualTo(77L);
        verify(checkoutRequestMapper, never()).selectByUserAndKey(anyLong(), anyString());
    }

    /** 同键同载荷：这是重试的正常答案，不是错误。 */
    @Test
    void sameKeyWithTheSamePayloadIsAReplay() {
        whenInsertHitsTheUniqueKey();
        when(checkoutRequestMapper.selectByUserAndKey(USER_ID, KEY))
                .thenReturn(existing("digest-a", CheckoutRequest.STATUS_SUBMITTED, 555L));

        IdempotencyClaim claim = checkoutService.claimIdempotencyKey(USER_ID, KEY, "digest-a");

        assertThat(claim.outcome()).isEqualTo(IdempotencyClaim.Outcome.REPLAY);
        assertThat(claim.existing().getOrderId()).isEqualTo(555L);
    }

    /**
     * 同键异载荷：只能拒绝。
     *
     * <p>前端复用了键但购物车已经变了。按新载荷悄悄下单，用户会买到他没确认的东西。</p>
     */
    @Test
    void sameKeyWithADifferentPayloadIsAConflict() {
        whenInsertHitsTheUniqueKey();
        when(checkoutRequestMapper.selectByUserAndKey(USER_ID, KEY))
                .thenReturn(existing("digest-a", CheckoutRequest.STATUS_SUBMITTED, 555L));

        IdempotencyClaim claim = checkoutService.claimIdempotencyKey(USER_ID, KEY, "digest-b");

        assertThat(claim.outcome()).isEqualTo(IdempotencyClaim.Outcome.CONFLICT);
    }

    /**
     * 撞了唯一键，回头却查不到记录。
     *
     * <p>不要吞掉，也不要当成 CLAIMED。这种情况说明数据库的状态和代码的假设不一致，
     * 继续往下走就是在未知状态上下单。把原异常抛出去，让它以 500 的形式被看见。</p>
     */
    @Test
    void aDuplicateThatCannotBeFoundIsRethrown() {
        whenInsertHitsTheUniqueKey();
        when(checkoutRequestMapper.selectByUserAndKey(USER_ID, KEY)).thenReturn(null);

        assertThatThrownBy(() -> checkoutService.claimIdempotencyKey(USER_ID, KEY, "digest-a"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    /**
     * 已有记录是「处理中」：既不是成功也不是失败。
     *
     * <p>{@code claimIdempotencyKey} 这一层只负责回答「这个键是不是已经被用过」，
     * 处理中同样算用过，所以是 REPLAY。要不要把它翻成 409 是编排层的判断，
     * 见下面 {@link #anInProgressReplayBecomesAConflictInsteadOfASecondOrder}。</p>
     */
    @Test
    void anInProgressRecordIsStillAReplay() {
        whenInsertHitsTheUniqueKey();
        when(checkoutRequestMapper.selectByUserAndKey(USER_ID, KEY))
                .thenReturn(existing("digest-a", CheckoutRequest.STATUS_IN_PROGRESS, null));

        IdempotencyClaim claim = checkoutService.claimIdempotencyKey(USER_ID, KEY, "digest-a");

        assertThat(claim.outcome()).isEqualTo(IdempotencyClaim.Outcome.REPLAY);
    }

    // =================================================================================
    // 第四组：整条编排 —— 这些用例覆盖的是已经写好的部分，
    //          但它们要等上面三组都绿了才能跑通。
    // =================================================================================

    /** 什么都没勾就提交，在查商品之前就该被挡掉。 */
    @Test
    void emptySelectionIsRejectedBeforeTouchingTheCatalog() {
        when(cartService.selectedItems(USER_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> checkoutService.submit(USER_ID, KEY, new CheckoutSubmitRequest(
                new BigDecimal("9.99"))))
                .isInstanceOf(CommerceException.class)
                .satisfies(thrown -> assertThat(((CommerceException) thrown).getCode())
                        .isEqualTo("CART_EMPTY"));

        verify(skuMapper, never()).selectByIds(anyList());
    }

    /** 没带 Idempotency-Key 就直接拒，不要给它编一个。 */
    @Test
    void aMissingIdempotencyKeyIsRejected() {
        assertThatThrownBy(() -> checkoutService.submit(USER_ID, "  ", new CheckoutSubmitRequest(
                new BigDecimal("9.99"))))
                .isInstanceOf(CommerceException.class)
                .satisfies(thrown -> assertThat(((CommerceException) thrown).getCode())
                        .isEqualTo("IDEMPOTENCY_KEY_REQUIRED"));
    }

    /** 用户看到 9.99，服务端重算是 19.90——必须拦下来让他重新确认。 */
    @Test
    void aPriceChangeSinceTheCartWasRenderedIsRejected() {
        givenSelectedCart(cartItem(SKU_A, 1));
        givenCatalog(sku(SKU_A, SPU_A, "19.90", 1), spu(SPU_A, "机械键盘", 1));
        givenAvailability(Map.of(SKU_A, 100));

        assertThatThrownBy(() -> checkoutService.submit(USER_ID, KEY, new CheckoutSubmitRequest(
                new BigDecimal("9.99"))))
                .isInstanceOf(CommerceException.class)
                .satisfies(thrown -> assertThat(((CommerceException) thrown).getCode())
                        .isEqualTo("PRICE_CHANGED"));

        verify(orderClient, never()).create(any());
    }

    /** 10.0 和 10.00 是同一笔钱，不能因为标度不同被判成价格变动。 */
    @Test
    void trailingZeroesInTheExpectedAmountAreNotAPriceChange() {
        givenSelectedCart(cartItem(SKU_A, 1));
        givenCatalog(sku(SKU_A, SPU_A, "10.00", 1), spu(SPU_A, "机械键盘", 1));
        givenAvailability(Map.of(SKU_A, 100));
        whenInsertSucceedsWithId(77L);
        when(orderClient.create(any())).thenAnswer(invocation -> {
            OrderCreateRequest request = invocation.getArgument(0);
            return new OrderCreateResponse(request.orderId(), "RESERVED", "ok", false);
        });

        CheckoutResultView result = checkoutService.submit(USER_ID, KEY,
                new CheckoutSubmitRequest(new BigDecimal("10.0")));

        assertThat(result.totalAmount()).isEqualByComparingTo("10.00");
    }

    /**
     * 下单成功：回填订单号、清掉已结算的条目。
     *
     * <p>往订单服务发的每个字段都只能来自快照。这里顺带钉住一点：
     * 订单里的单价是服务端查到的 10.00，不是请求里带上来的任何数字。</p>
     */
    @Test
    void aSuccessfulCheckoutRecordsTheOrderAndClearsOnlyThoseItems() {
        givenSelectedCart(cartItem(SKU_A, 2));
        givenCatalog(sku(SKU_A, SPU_A, "10.00", 1), spu(SPU_A, "机械键盘", 1));
        givenAvailability(Map.of(SKU_A, 100));
        whenInsertSucceedsWithId(77L);
        when(orderClient.create(any())).thenAnswer(invocation -> {
            OrderCreateRequest request = invocation.getArgument(0);
            assertThat(request.userId()).isEqualTo(USER_ID);
            assertThat(request.totalAmount()).isEqualByComparingTo("20.00");
            assertThat(request.items()).hasSize(1);
            assertThat(request.items().get(0).price()).isEqualByComparingTo("10.00");
            assertThat(request.items().get(0).nameSnapshot()).isEqualTo("机械键盘");
            return new OrderCreateResponse(request.orderId(), "RESERVED", "ok", false);
        });

        CheckoutResultView result = checkoutService.submit(USER_ID, KEY,
                new CheckoutSubmitRequest(new BigDecimal("20.00")));

        assertThat(result.orderId()).isNotNull();
        assertThat(result.state()).isEqualTo("RESERVED");
        verify(checkoutRequestMapper).markSubmitted(77L, result.orderId());
        verify(cartService).removeCheckedOut(USER_ID, List.of(SKU_A));
    }

    /**
     * 订单已经建好了，清购物车失败。
     *
     * <p>这时候抛异常是最糟的选择：用户看到失败，于是再下一单，
     * 而第一单已经占着库存等付款。清车失败只能记日志。</p>
     */
    @Test
    void aFailureToClearTheCartDoesNotFailTheCheckout() {
        givenSelectedCart(cartItem(SKU_A, 1));
        givenCatalog(sku(SKU_A, SPU_A, "10.00", 1), spu(SPU_A, "机械键盘", 1));
        givenAvailability(Map.of(SKU_A, 100));
        whenInsertSucceedsWithId(77L);
        when(orderClient.create(any())).thenAnswer(invocation -> new OrderCreateResponse(
                ((OrderCreateRequest) invocation.getArgument(0)).orderId(), "RESERVED", "ok", false));
        doThrow(new IllegalStateException("redis down"))
                .when(cartService).removeCheckedOut(anyLong(), anyList());

        CheckoutResultView result = checkoutService.submit(USER_ID, KEY,
                new CheckoutSubmitRequest(new BigDecimal("10.00")));

        assertThat(result.state()).isEqualTo("RESERVED");
    }

    /**
     * <b>调订单服务抛异常时，幂等记录必须留在「处理中」。</b>
     *
     * <p>调用失败不等于订单没建成——请求可能已经落到对面了，只是响应在网络上丢了。
     * 标记成「已拒绝」的话，用户重试会拿到一个确定的失败答案，
     * 而那张订单其实存在、占着库存、等着超时释放。宁可说「不知道」。</p>
     */
    @Test
    void anOrderServiceFailureLeavesTheClaimInProgress() {
        givenSelectedCart(cartItem(SKU_A, 1));
        givenCatalog(sku(SKU_A, SPU_A, "10.00", 1), spu(SPU_A, "机械键盘", 1));
        givenAvailability(Map.of(SKU_A, 100));
        whenInsertSucceedsWithId(77L);
        when(orderClient.create(any())).thenThrow(new IllegalStateException("connect timed out"));

        assertThatThrownBy(() -> checkoutService.submit(USER_ID, KEY,
                new CheckoutSubmitRequest(new BigDecimal("10.00"))))
                .isInstanceOf(CommerceException.class)
                .satisfies(thrown -> assertThat(((CommerceException) thrown).getCode())
                        .isEqualTo("CHECKOUT_RESULT_UNKNOWN"));

        verify(checkoutRequestMapper, never()).markRejected(anyLong(), any(), anyString());
        verify(checkoutRequestMapper, never()).markSubmitted(anyLong(), anyLong());
    }

    /**
     * 下游返回「补偿中」：它自己也不知道库存占上了没有。
     *
     * <p>和调用抛异常是同一类情况——不确定。所以同样保持「处理中」，
     * 不能记成拒绝。这一条和下面那条 FAILED 的区别值得看清楚：
     * FAILED 是「确定没占上」，PENDING_COMPENSATION 是「不知道」，
     * 把两者归成一类处理，就是在不确定的时候给用户一个确定的答案。</p>
     */
    @Test
    void aPendingCompensationIsReportedAsUnknownRatherThanRejected() {
        givenSelectedCart(cartItem(SKU_A, 1));
        givenCatalog(sku(SKU_A, SPU_A, "10.00", 1), spu(SPU_A, "机械键盘", 1));
        givenAvailability(Map.of(SKU_A, 100));
        whenInsertSucceedsWithId(77L);
        when(orderClient.create(any())).thenAnswer(invocation -> new OrderCreateResponse(
                ((OrderCreateRequest) invocation.getArgument(0)).orderId(),
                "PENDING_COMPENSATION", "库存回滚未完成", false));

        assertThatThrownBy(() -> checkoutService.submit(USER_ID, KEY,
                new CheckoutSubmitRequest(new BigDecimal("10.00"))))
                .isInstanceOf(CommerceException.class)
                .satisfies(thrown -> assertThat(((CommerceException) thrown).getCode())
                        .isEqualTo("CHECKOUT_RESULT_UNKNOWN"));

        verify(checkoutRequestMapper, never()).markRejected(anyLong(), any(), anyString());
        verify(cartService, never()).removeCheckedOut(anyLong(), anyList());
    }

    /**
     * 下游回了个空响应体。
     *
     * <p>这一条容易被写成「记成拒绝，反正没拿到结果」。但空响应体和超时没有区别：
     * 请求可能已经执行完了，只是回来的东西不对。我们没有任何依据替订单服务
     * 下「没建成」的结论，所以仍然保持「处理中」。</p>
     */
    @Test
    void anEmptyResponseIsUnknownRatherThanRejected() {
        givenSelectedCart(cartItem(SKU_A, 1));
        givenCatalog(sku(SKU_A, SPU_A, "10.00", 1), spu(SPU_A, "机械键盘", 1));
        givenAvailability(Map.of(SKU_A, 100));
        whenInsertSucceedsWithId(77L);
        when(orderClient.create(any())).thenReturn(null);

        assertThatThrownBy(() -> checkoutService.submit(USER_ID, KEY,
                new CheckoutSubmitRequest(new BigDecimal("10.00"))))
                .isInstanceOf(CommerceException.class)
                .satisfies(thrown -> assertThat(((CommerceException) thrown).getCode())
                        .isEqualTo("CHECKOUT_RESULT_UNKNOWN"));

        verify(checkoutRequestMapper, never()).markRejected(anyLong(), any(), anyString());
        verify(checkoutRequestMapper, never()).markSubmitted(anyLong(), anyLong());
        verify(cartService, never()).removeCheckedOut(anyLong(), anyList());
    }

    /** 下游明确拒绝（库存不足）：记下原因，返回带 state 的结果而不是异常。 */
    @Test
    void aRejectionFromOrderServiceIsRecordedAndReturned() {
        givenSelectedCart(cartItem(SKU_A, 1));
        givenCatalog(sku(SKU_A, SPU_A, "10.00", 1), spu(SPU_A, "机械键盘", 1));
        givenAvailability(Map.of(SKU_A, 100));
        whenInsertSucceedsWithId(77L);
        when(orderClient.create(any())).thenAnswer(invocation -> new OrderCreateResponse(
                ((OrderCreateRequest) invocation.getArgument(0)).orderId(), "FAILED", "库存不足", false));

        CheckoutResultView result = checkoutService.submit(USER_ID, KEY,
                new CheckoutSubmitRequest(new BigDecimal("10.00")));

        assertThat(result.state()).isEqualTo("FAILED");
        assertThat(result.message()).isEqualTo("库存不足");
        verify(checkoutRequestMapper).markRejected(eq(77L), any(), eq("库存不足"));
        verify(cartService, never()).removeCheckedOut(anyLong(), anyList());
    }

    /** 同键同载荷重放：不能再下一单，直接把上次的结果还回去。 */
    @Test
    void aReplayReturnsTheEarlierOrderWithoutPlacingAnother() {
        givenSelectedCart(cartItem(SKU_A, 1));
        givenCatalog(sku(SKU_A, SPU_A, "10.00", 1), spu(SPU_A, "机械键盘", 1));
        givenAvailability(Map.of(SKU_A, 100));
        whenInsertHitsTheUniqueKey();
        when(checkoutRequestMapper.selectByUserAndKey(anyLong(), anyString()))
                .thenAnswer(invocation -> {
                    // 摘要由他的 digest 算出来，测试没法预先写死，
                    // 所以这里用「和刚才插入时用的那个一样」来构造重放。
                    return existing(lastAttemptedDigest, CheckoutRequest.STATUS_SUBMITTED, 555L);
                });

        CheckoutResultView result = checkoutService.submit(USER_ID, KEY,
                new CheckoutSubmitRequest(new BigDecimal("10.00")));

        assertThat(result.replayed()).isTrue();
        assertThat(result.orderId()).isEqualTo(555L);
        verify(orderClient, never()).create(any());
    }

    @Test
    void aReplayStillWorksAfterSuccessfulCheckoutClearsTheCart() {
        givenSelectedCart();
        CheckoutRequest previous = existing("previous-digest", CheckoutRequest.STATUS_SUBMITTED, 555L);
        previous.setTotalAmount(new BigDecimal("10.00"));
        when(checkoutRequestMapper.selectByUserAndKey(USER_ID, KEY)).thenReturn(previous);

        CheckoutResultView result = checkoutService.submit(USER_ID, KEY,
                new CheckoutSubmitRequest(new BigDecimal("10.0")));

        assertThat(result.orderId()).isEqualTo(555L);
        assertThat(result.totalAmount()).isEqualByComparingTo("10.00");
        assertThat(result.replayed()).isTrue();
        verify(orderClient, never()).create(any());
    }

    @Test
    void anEmptyCartCannotReplayAKeyWithADifferentAmount() {
        givenSelectedCart();
        CheckoutRequest previous = existing("previous-digest", CheckoutRequest.STATUS_SUBMITTED, 555L);
        previous.setTotalAmount(new BigDecimal("10.00"));
        when(checkoutRequestMapper.selectByUserAndKey(USER_ID, KEY)).thenReturn(previous);

        assertThatThrownBy(() -> checkoutService.submit(USER_ID, KEY,
                new CheckoutSubmitRequest(new BigDecimal("11.00"))))
                .isInstanceOf(CommerceException.class)
                .satisfies(thrown -> assertThat(((CommerceException) thrown).getCode())
                        .isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        verify(orderClient, never()).create(any());
    }

    /**
     * 已有记录停在「处理中」：编排层把它翻成 409，而不是替用户重下一单。
     *
     * <p>上一单可能已经成功了，只是我们没来得及记下来。
     * 这里自动重下的代价是重复订单加重复占库存，用户可能真的付两次钱。</p>
     */
    @Test
    void anInProgressReplayBecomesAConflictInsteadOfASecondOrder() {
        givenSelectedCart(cartItem(SKU_A, 1));
        givenCatalog(sku(SKU_A, SPU_A, "10.00", 1), spu(SPU_A, "机械键盘", 1));
        givenAvailability(Map.of(SKU_A, 100));
        whenInsertHitsTheUniqueKey();
        when(checkoutRequestMapper.selectByUserAndKey(anyLong(), anyString()))
                .thenAnswer(invocation -> existing(lastAttemptedDigest,
                        CheckoutRequest.STATUS_IN_PROGRESS, null));

        assertThatThrownBy(() -> checkoutService.submit(USER_ID, KEY,
                new CheckoutSubmitRequest(new BigDecimal("10.00"))))
                .isInstanceOf(CommerceException.class)
                .satisfies(thrown -> {
                    CommerceException exception = (CommerceException) thrown;
                    assertThat(exception.getCode()).isEqualTo("CHECKOUT_IN_PROGRESS");
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });

        verify(orderClient, never()).create(any());
    }

    /** 同键异载荷：409，绝不按新载荷下单。 */
    @Test
    void reusingAKeyForADifferentCartIsRejected() {
        givenSelectedCart(cartItem(SKU_A, 1));
        givenCatalog(sku(SKU_A, SPU_A, "10.00", 1), spu(SPU_A, "机械键盘", 1));
        givenAvailability(Map.of(SKU_A, 100));
        whenInsertHitsTheUniqueKey();
        when(checkoutRequestMapper.selectByUserAndKey(anyLong(), anyString()))
                .thenReturn(existing("a-digest-from-an-entirely-different-cart",
                        CheckoutRequest.STATUS_SUBMITTED, 555L));

        assertThatThrownBy(() -> checkoutService.submit(USER_ID, KEY,
                new CheckoutSubmitRequest(new BigDecimal("10.00"))))
                .isInstanceOf(CommerceException.class)
                .satisfies(thrown -> assertThat(((CommerceException) thrown).getCode())
                        .isEqualTo("IDEMPOTENCY_KEY_REUSED"));

        verify(orderClient, never()).create(any());
    }

    // =================================================================================
    // 夹具
    // =================================================================================

    /** 记下最后一次尝试插入时用的摘要，好在重放用例里构造「一模一样的载荷」。 */
    private String lastAttemptedDigest;

    private void givenSelectedCart(CartItemSnapshot... items) {
        when(cartService.selectedItems(USER_ID)).thenReturn(List.of(items));
    }

    private void givenCatalog(ProductSku sku, ProductSpu spu) {
        when(skuMapper.selectByIds(anyList())).thenReturn(List.of(sku));
        when(spuMapper.selectById(spu.getId())).thenReturn(spu);
    }

    private void givenAvailability(Map<Long, Integer> stock) {
        when(availabilityService.available(anyList())).thenAnswer(invocation -> {
            Collection<?> ids = invocation.getArgument(0);
            Map<Long, Integer> result = new LinkedHashMap<>();
            ids.forEach(id -> result.put((Long) id, stock.get(id)));
            return result;
        });
        when(availabilityService.available(any(Long.class)))
                .thenAnswer(invocation -> stock.get(invocation.<Long>getArgument(0)));
    }

    /** {@code Map.of} 不接受 null 值，而「库存未知」恰恰就是 null。 */
    private static Map<Long, Integer> singletonMapWithNull(Long skuId) {
        Map<Long, Integer> stock = new LinkedHashMap<>();
        stock.put(skuId, null);
        return stock;
    }

    private void whenInsertSucceedsWithId(long generatedId) {
        when(checkoutRequestMapper.insertClaim(any())).thenAnswer(invocation -> {
            CheckoutRequest claim = invocation.getArgument(0);
            lastAttemptedDigest = claim.getRequestDigest();
            claim.setId(generatedId);
            return 1;
        });
    }

    private void whenInsertHitsTheUniqueKey() {
        when(checkoutRequestMapper.insertClaim(any())).thenAnswer(invocation -> {
            lastAttemptedDigest = ((CheckoutRequest) invocation.getArgument(0)).getRequestDigest();
            throw new DuplicateKeyException("Duplicate entry for key 'uk_checkout_user_key'");
        });
    }

    private static CheckoutRequest existing(String digest, int status, Long orderId) {
        CheckoutRequest record = new CheckoutRequest();
        record.setId(77L);
        record.setUserId(USER_ID);
        record.setIdempotencyKey(KEY);
        record.setRequestDigest(digest);
        record.setStatus(status);
        record.setOrderId(orderId);
        return record;
    }

    private static CartItemSnapshot cartItem(long skuId, int quantity) {
        return new CartItemSnapshot(skuId, quantity, true);
    }

    private static CheckoutLine line(long skuId, int quantity, String price) {
        return new CheckoutLine(skuId, SPU_A, quantity, new BigDecimal(price), "机械键盘", "{}");
    }

    private static ProductSku sku(long id, long spuId, String price, int status) {
        ProductSku sku = new ProductSku();
        sku.setId(id);
        sku.setSpuId(spuId);
        sku.setSkuCode("SKU-" + id);
        sku.setSpecJson("{\"色\":\"黑\"}");
        sku.setPrice(new BigDecimal(price));
        sku.setStatus(status);
        return sku;
    }

    private static ProductSpu spu(long id, String name, int status) {
        ProductSpu spu = new ProductSpu();
        spu.setId(id);
        spu.setName(name);
        spu.setStatus(status);
        return spu;
    }
}
