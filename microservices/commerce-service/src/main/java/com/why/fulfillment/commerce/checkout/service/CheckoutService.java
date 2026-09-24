package com.why.fulfillment.commerce.checkout.service;

import com.why.fulfillment.api.order.OrderClient;
import com.why.fulfillment.api.order.OrderCreateItem;
import com.why.fulfillment.api.order.OrderCreateRequest;
import com.why.fulfillment.api.order.OrderCreateResponse;
import com.why.fulfillment.commerce.cart.service.CartItemSnapshot;
import com.why.fulfillment.commerce.cart.service.CartService;
import com.why.fulfillment.commerce.checkout.dto.CheckoutResultView;
import com.why.fulfillment.commerce.checkout.dto.CheckoutSubmitRequest;
import com.why.fulfillment.commerce.checkout.entity.CheckoutRequest;
import com.why.fulfillment.commerce.checkout.mapper.CheckoutRequestMapper;
import com.why.fulfillment.commerce.common.CommerceException;
import com.why.fulfillment.commerce.product.mapper.ProductSkuMapper;
import com.why.fulfillment.commerce.product.mapper.ProductSpuMapper;
import com.why.fulfillment.commerce.product.entity.ProductSku;
import com.why.fulfillment.commerce.product.entity.ProductSpu;
import com.why.fulfillment.commerce.product.service.SkuAvailabilityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 结算：把购物车里已勾选的条目变成一张订单。
 *
 * <p>结算会重新校验商品、固定价格快照、计算请求摘要并认领幂等键。
 * 对应的失败用例在 {@code CheckoutServiceTest} 中覆盖。</p>
 *
 * <h2>为什么必须「重新校验」</h2>
 * <p>用户打开结算页到点下提交之间可能隔了几分钟甚至几小时。这段时间里商品可能
 * 涨价、降价、下架，库存可能被别人买光。购物车里存的只有 skuId 和数量，
 * 没有价格——价格是每次读购物车现查的，所以前端显示的价格是「那一刻的价格」，
 * 不是承诺。提交时必须再查一次，并且以这次查到的为准。</p>
 *
 * <h2>为什么校验之后还要「快照」</h2>
 * <p>校验查到的价格必须当场固定下来，写进订单。如果只是校验一下、下单时再查一次，
 * 那两次查询之间的改价会让用户按一个他没见过的价格付款。快照是「用户同意的内容」
 * 的唯一载体。</p>
 *
 * <h2>结算和库存预占的分工</h2>
 * <p>这里查到的可售库存只用来提前拦掉明显买不到的情况，<b>它不构成任何保证</b>。
 * 真正决定能不能买到的是 Inventory 的原子条件更新（{@code stock >= count} 写在
 * UPDATE 的 WHERE 里）。在这里写「先查库存够不够、够就下单」是典型的
 * check-then-act：两个人同时查到还剩 1 件，然后都下单。</p>
 */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    private static final int MAX_ERROR_LENGTH = 500;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    /**
     * 订单服务返回的「补偿中」——它自己也还不知道结果。
     *
     * <p>用字符串比而不是引 {@code ReservationStatus}：那个枚举在 order-service 模块里，
     * Commerce 依赖它就等于依赖了对方的内部领域模型。跨服务的契约只能是
     * fulfillment-api 里那几个 record 加上约定好的状态字面量。</p>
     */
    private static final String STATE_PENDING_COMPENSATION = "PENDING_COMPENSATION";

    private final CartService cartService;
    private final ProductSkuMapper skuMapper;
    private final ProductSpuMapper spuMapper;
    private final SkuAvailabilityService availabilityService;
    private final CheckoutRequestMapper checkoutRequestMapper;
    private final OrderClient orderClient;
    private final OrderIdGenerator orderIdGenerator;
    private final long orderTimeoutSeconds;

    public CheckoutService(CartService cartService,
                           ProductSkuMapper skuMapper,
                           ProductSpuMapper spuMapper,
                           SkuAvailabilityService availabilityService,
                           CheckoutRequestMapper checkoutRequestMapper,
                           OrderClient orderClient,
                           OrderIdGenerator orderIdGenerator,
                           @Value("${commerce.checkout.order-timeout-seconds:1800}") long orderTimeoutSeconds) {
        this.cartService = cartService;
        this.skuMapper = skuMapper;
        this.spuMapper = spuMapper;
        this.availabilityService = availabilityService;
        this.checkoutRequestMapper = checkoutRequestMapper;
        this.orderClient = orderClient;
        this.orderIdGenerator = orderIdGenerator;
        this.orderTimeoutSeconds = orderTimeoutSeconds;
    }

    // =================================================================================
    // 编排：已完成
    // =================================================================================

    /**
     * 提交结算。
     *
     * <p><b>整个方法没有 {@code @Transactional}，是故意的。</b>中间要调订单服务，
     * 而订单服务又要调库存服务。把本地事务开在一次跨服务 HTTP 调用之外，
     * 意味着这个数据库连接和行锁要被占住整个网络往返的时间；下游慢一点，
     * 连接池就被拖干，然后所有接口一起挂掉——这是最常见的一种雪崩。</p>
     *
     * <p>代价是没有原子性：幂等记录写进去了、订单没建成，是可能发生的。
     * 这正是 {@code checkout_request.status} 需要「处理中」这个状态的原因。</p>
     */
    public CheckoutResultView submit(long userId, String idempotencyKey, CheckoutSubmitRequest request) {
        String key = normalizeKey(idempotencyKey);

        List<CartItemSnapshot> selected = cartService.selectedItems(userId);
        if (selected.isEmpty()) {
            CheckoutRequest existing = checkoutRequestMapper.selectByUserAndKey(userId, key);
            if (existing != null) {
                if (existing.getTotalAmount() == null || request.expectedAmount() == null
                        || existing.getTotalAmount().compareTo(request.expectedAmount()) != 0) {
                    throw CommerceException.conflict("IDEMPOTENCY_KEY_REUSED",
                            "这个提交凭证已经用于另一笔结算，请刷新购物车后重试");
                }
                return replayOf(existing, existing.getTotalAmount());
            }
            throw CommerceException.badRequest("CART_EMPTY", "没有勾选任何商品");
        }

        List<CheckoutLine> lines = revalidate(userId, selected);
        BigDecimal total = sum(lines);
        requireAmountUnchanged(request.expectedAmount(), total);

        String digest = digest(userId, lines, total);
        IdempotencyClaim claim = claimIdempotencyKey(userId, key, digest, total);

        switch (claim.outcome()) {
            case REPLAY -> {
                return replayOf(claim.existing(), total);
            }
            case CONFLICT -> throw CommerceException.conflict("IDEMPOTENCY_KEY_REUSED",
                    "这个提交凭证已经用于另一笔结算，请刷新购物车后重试");
            case CLAIMED -> {
                // 继续往下走
            }
        }

        long orderId = orderIdGenerator.next();
        OrderCreateResponse response;
        try {
            response = orderClient.create(toOrderRequest(orderId, userId, total, lines));
        } catch (RuntimeException exception) {
            // 调用失败不等于订单没建成——请求可能已经到了对面，只是响应丢了。
            // 所以这条幂等记录**保持「处理中」**，不标记为拒绝。
            // 标记成拒绝的话，用户重试会拿到「已拒绝」，而那张订单其实存在并且占着库存。
            log.warn("Checkout call to order-service failed for user {} order {}: {}",
                    userId, orderId, exception.toString());
            throw CommerceException.conflict("CHECKOUT_RESULT_UNKNOWN",
                    "下单结果未知，请稍后在订单列表确认，不要重复提交");
        }

        if (response == null) {
            // Feign 给了个空响应体。这和上面抛异常是同一类情况：我们不知道对面
            // 到底建没建成。**不要**标记为拒绝——标了就等于替订单服务下了「没建成」
            // 的结论，而那张订单可能存在并且占着库存。保持「处理中」，让重试去认领。
            log.warn("Checkout got an empty response from order-service for user {} order {}", userId, orderId);
            throw CommerceException.conflict("CHECKOUT_RESULT_UNKNOWN",
                    "下单结果未知，请稍后在订单列表确认，不要重复提交");
        }

        if (response.reserved()) {
            checkoutRequestMapper.markSubmitted(claim.claimId(), orderId);
            clearCheckedOutItems(orderId, userId, lines);
            return new CheckoutResultView(orderId, response.state(), "下单成功，请尽快付款",
                    total, response.replayed());
        }

        if (STATE_PENDING_COMPENSATION.equals(response.state())) {
            // 订单服务自己也不知道库存到底占上了没有，正在补偿。
            // 这里跟着说「不知道」，同样**保持「处理中」**：
            // 记成拒绝的话，补偿如果最终判定为「已预占」，就出现了一张
            // 我们这边标记为失败、实际却存在的订单。
            log.warn("Order {} for user {} came back as {}", orderId, userId, response.state());
            throw CommerceException.conflict("CHECKOUT_RESULT_UNKNOWN",
                    "下单结果未知，请稍后在订单列表确认，不要重复提交");
        }

        // 剩下的是确定的失败：FAILED（库存不足）、COMPENSATED（已回滚）、CONFLICT。
        checkoutRequestMapper.markRejected(claim.claimId(), orderId, truncate(response.message()));
        return new CheckoutResultView(orderId, response.state(),
                response.message() == null ? "下单失败" : response.message(), total, response.replayed());
    }

    /**
     * 下单成功后清掉已结算的条目。
     *
     * <p>清车失败不能让整个结算失败：订单已经建好、库存已经占上了，
     * 这时候抛异常只会让用户以为没下单成功，然后再下一单。</p>
     */
    private void clearCheckedOutItems(long orderId, long userId, List<CheckoutLine> lines) {
        try {
            cartService.removeCheckedOut(userId, lines.stream().map(CheckoutLine::skuId).toList());
        } catch (RuntimeException exception) {
            log.warn("Order {} was created but clearing the cart failed for user {}: {}",
                    orderId, userId, exception.toString());
        }
    }

    private CheckoutResultView replayOf(CheckoutRequest existing, BigDecimal total) {
        if (existing == null) {
            throw new IllegalStateException("a REPLAY claim must carry the existing record");
        }
        Integer status = existing.getStatus();
        if (status != null && status == CheckoutRequest.STATUS_SUBMITTED) {
            return new CheckoutResultView(existing.getOrderId(), "RESERVED",
                    "这笔结算已经提交过了", total, true);
        }
        if (status != null && status == CheckoutRequest.STATUS_REJECTED) {
            return new CheckoutResultView(existing.getOrderId(), "FAILED",
                    existing.getLastError() == null ? "这笔结算已被拒绝" : existing.getLastError(),
                    total, true);
        }
        // status = 处理中。上一次请求没能走完，结果未知。
        // 这里不能替用户重新下一单：上一单可能已经成功了，只是我们没记下来。
        throw CommerceException.conflict("CHECKOUT_IN_PROGRESS",
                "上一次提交结果未知，请先到订单列表确认，不要重复提交");
    }

    private OrderCreateRequest toOrderRequest(long orderId, long userId, BigDecimal total,
                                              List<CheckoutLine> lines) {
        return new OrderCreateRequest(orderId, userId, total, orderTimeoutSeconds,
                lines.stream()
                        .map(line -> new OrderCreateItem(line.skuId(), line.spuId(), line.quantity(),
                                line.price(), line.nameSnapshot(), line.specSnapshot()))
                        .toList());
    }

    static BigDecimal sum(List<CheckoutLine> lines) {
        return lines.stream().map(CheckoutLine::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 用户看到的金额必须和服务端重新算出来的一致。
     *
     * <p>用 compareTo 不用 equals：10.0 和 10.00 是同一笔钱，但 BigDecimal 的 equals
     * 认为它们不相等，会把正确的请求判成金额不符。</p>
     */
    static void requireAmountUnchanged(BigDecimal expected, BigDecimal actual) {
        if (expected == null || expected.compareTo(actual) != 0) {
            throw CommerceException.conflict("PRICE_CHANGED",
                    "商品价格已变动，当前应付 " + actual.toPlainString() + " 元，请确认后重新提交");
        }
    }

    private static String normalizeKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw CommerceException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "缺少 Idempotency-Key");
        }
        String key = idempotencyKey.trim();
        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw CommerceException.badRequest("IDEMPOTENCY_KEY_TOO_LONG",
                    "Idempotency-Key 最长 " + MAX_IDEMPOTENCY_KEY_LENGTH + " 个字符");
        }
        return key;
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }

    // =================================================================================
    // 结算规则与幂等键认领。
    // =================================================================================

    /**
     * 重新校验购物车条目，并产出价格快照。
     *
     * <p>入参是购物车里已勾选的条目（只有 skuId、数量、勾选状态），
     * 出参是可以直接下单的 {@link CheckoutLine} 列表。</p>
     *
     * <p>要处理的情况，逐条对应一个测试：</p>
     * <ol>
     *   <li><b>SKU 不存在</b>（被硬删了）→ 抛 {@code SKU_NOT_FOUND}（404）。</li>
     *   <li><b>SKU 或其 SPU 已下架</b> → 抛 {@code SKU_NOT_ON_SALE}（400），消息里带上商品名，
     *       否则用户面对一句「有商品已下架」不知道该去删哪一件。
     *       注意 SPU 下架时 SKU 可能还是上架状态，两个都要查。</li>
     *   <li><b>数量非法</b>（≤ 0）→ 抛 {@code INVALID_QUANTITY}（400）。
     *       数据库有 {@code CHECK (quantity > 0)}，但校验不能建立在
     *       「上游一定没问题」上——这行代码的职责就是不信任输入。</li>
     *   <li><b>可售库存明显不足</b> → 抛 {@code INSUFFICIENT_STOCK}（400）。
     *       注意 {@link SkuAvailabilityService#available} 返回 <b>null 表示「不知道」</b>
     *       （Inventory 不可达），不是 0。把 null 当 0 会在库存服务抖动时
     *       把所有商品显示成售罄并拒绝所有下单。不知道就放行，让 Inventory 的
     *       原子预占去裁决——那才是真正说了算的地方。</li>
     *   <li>全部通过 → 用<b>这一刻查到的</b>价格、商品名、规格构造 CheckoutLine。
     *       价格来自 {@code ProductSku#getPrice()}，名字来自 SPU，规格来自
     *       {@code ProductSku#getSpecJson()}。</li>
     * </ol>
     *
     * <p>实现提示：用 {@code skuMapper.selectByIds(...)} 一次查完所有 SKU，
     * 再按 spuId 去重后查 SPU。逐个 selectById 会变成 N+1。</p>
     *
     * <p><b>顺序也要想清楚：</b>下架和库存这两类检查，哪个先报？
     * 一次只报一条错，用户就要提交 N 次才能把问题清干净。
     * 这个取舍没有标准答案，但你要能说出你选了哪种、为什么。</p>
     */
    List<CheckoutLine> revalidate(long userId, List<CartItemSnapshot> selected) {
        if (selected == null || selected.isEmpty()) {
            throw CommerceException.badRequest("CART_EMPTY", "没有勾选任何商品");
        }
        for (CartItemSnapshot item : selected) {
            if (item.quantity() == null || item.quantity() <= 0) {
                throw CommerceException.badRequest("INVALID_QUANTITY", "商品数量必须大于 0");
            }
        }
        List<Long> skuIds = selected.stream().map(CartItemSnapshot::skuId).distinct().toList();
        Map<Long, ProductSku> skus = skuMapper.selectByIds(skuIds)
                .stream().collect(Collectors.toMap(ProductSku::getId, Function.identity()));
        Map<Long, ProductSpu> spus = new HashMap<>();
        for (CartItemSnapshot item : selected) {
            ProductSku sku = skus.get(item.skuId());
            if (sku == null) {
                throw CommerceException.notFound("SKU_NOT_FOUND", "商品规格 " + item.skuId() + " 不存在");
            }
            ProductSpu spu = spus.computeIfAbsent(sku.getSpuId(), spuMapper::selectById);
            String name = spu == null ? "商品 " + item.skuId() : spu.getName();
            if (!Integer.valueOf(1).equals(sku.getStatus()) || spu == null
                    || !Integer.valueOf(1).equals(spu.getStatus())) {
                throw CommerceException.badRequest("SKU_NOT_ON_SALE", name + " 已下架");
            }
        }
        Map<Long, Integer> available = availabilityService.available(skuIds);
        return selected.stream().map(item -> {
            Integer stock = available == null ? null : available.get(item.skuId());
            if (stock != null && stock < item.quantity()) {
                throw CommerceException.badRequest("INSUFFICIENT_STOCK",
                        spus.get(skus.get(item.skuId()).getSpuId()).getName() + " 库存不足");
            }
            ProductSku sku = skus.get(item.skuId());
            ProductSpu spu = spus.get(sku.getSpuId());
            return new CheckoutLine(sku.getId(), sku.getSpuId(), item.quantity(),
                    sku.getPrice(), spu.getName(), sku.getSpecJson());
        }).toList();
    }

    /**
     * 计算请求摘要，用于区分「同键同载荷」和「同键异载荷」。
     *
     * <p>返回 64 个字符的小写十六进制 SHA-256。列定义是 {@code CHAR(64)}，
     * 长度对不上会在写库时报错。</p>
     *
     * <p>要求：</p>
     * <ol>
     *   <li><b>必须规范化。</b>同样的内容换个顺序必须得到同样的摘要，
     *       否则前端换个遍历顺序重试就会被判成冲突。按 skuId 排序。</li>
     *   <li><b>金额要用统一标度。</b>{@code 5} 和 {@code 5.00} 是同一个价格，
     *       但 {@code BigDecimal#toString} 会给出不同的字符串。
     *       用 {@code setScale(2)} 之后再拼。</li>
     *   <li><b>字段之间要有分隔符，且分隔符不能出现在字段内容里。</b>
     *       不然 {@code ("1","23")} 和 {@code ("12","3")} 会拼成同一个串。
     *       商品名是用户可见的任意文本，这条尤其要小心——
     *       更稳的做法是只摘要 userId / skuId / 数量 / 价格 / 总额这些结构化字段，
     *       不把商品名放进去（想想为什么：名字变了算不算「不同的请求」？
     *       这个问题和订单那边 samePayload 为什么不比较快照是同一个问题）。</li>
     * </ol>
     *
     * <p>用 JDK 的 {@code MessageDigest.getInstance("SHA-256")}，不要引新依赖。
     * 编码固定成 UTF-8，不要用平台默认编码——换台机器结果就变了。</p>
     */
    String digest(long userId, List<CheckoutLine> lines, BigDecimal total) {
        StringBuilder canonical = new StringBuilder().append(userId).append('\n')
                .append(total.setScale(2).toPlainString()).append('\n');
        lines.stream().sorted(Comparator.comparing(CheckoutLine::skuId))
                .forEach(line -> canonical.append(line.skuId()).append(':')
                        .append(line.quantity()).append(':')
                        .append(line.price().setScale(2).toPlainString()).append('\n'));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * 认领幂等键。
     *
     * <p>这是整条链路里唯一一处真正的并发控制点，也是最容易写错的地方。</p>
     *
     * <p><b>不要写成「先查有没有，没有再插」。</b>同一个用户双击提交的两个请求
     * 可以同时查到「没有」，然后都去插，其中一个撞唯一键炸出 500。
     * 正确的写法是<b>直接插</b>，让 {@code uk_checkout_user_key} 当裁判：</p>
     * <ol>
     *   <li>调 {@code checkoutRequestMapper.insertClaim(...)}。插成功 →
     *       {@link IdempotencyClaim#claimed} ，claimId 取回填到实体上的自增主键
     *       （mapper 上已经配了 {@code useGeneratedKeys}）。</li>
     *   <li>捕获 {@code org.springframework.dao.DuplicateKeyException} → 说明这个键已经有人用了。
     *       此时再 {@code selectByUserAndKey} 把已有记录查出来：
     *       <ul>
     *         <li>摘要相同 → {@link IdempotencyClaim#replay}，把已有记录带回去。</li>
     *         <li>摘要不同 → {@link IdempotencyClaim#conflict}。</li>
     *       </ul>
     *       只有在这个分支里才需要查询——「先插后查」和「先查后插」的区别就在这儿：
     *       查询只发生在确定有冲突之后，不存在窗口。</li>
     *   <li>查不到（并发删除等极端情况）→ 把原异常抛出去，不要假装成功。</li>
     * </ol>
     *
     * <p>摘要比较用 {@code equals} 就够——它不是签名，对手是重复提交，不是伪造者。
     * 这和 JWT 那边为什么要用 {@code MessageDigest.isEqual} 是不同的场景，
     * 面试被问到时要能分清。</p>
     *
     * <p><b>还要想一个问题：</b>查到的记录 status 是「处理中」时该返回什么？
     * 它既不是成功也不是失败。测试里钉了一种答案，先自己想一遍再去看。</p>
     */
    IdempotencyClaim claimIdempotencyKey(long userId, String idempotencyKey, String digest) {
        return claimIdempotencyKey(userId, idempotencyKey, digest, null);
    }

    private IdempotencyClaim claimIdempotencyKey(long userId, String idempotencyKey,
                                               String digest, BigDecimal total) {
        CheckoutRequest claim = new CheckoutRequest();
        claim.setUserId(userId);
        claim.setIdempotencyKey(idempotencyKey);
        claim.setRequestDigest(digest);
        claim.setTotalAmount(total);
        try {
            checkoutRequestMapper.insertClaim(claim);
            return IdempotencyClaim.claimed(claim.getId());
        } catch (DuplicateKeyException exception) {
            CheckoutRequest existing = checkoutRequestMapper.selectByUserAndKey(userId, idempotencyKey);
            if (existing == null) {
                throw exception;
            }
            return digest.equals(existing.getRequestDigest())
                    ? IdempotencyClaim.replay(existing) : IdempotencyClaim.conflict(existing);
        }
    }

    /**
     * 【待实现 4／4】把重新校验的结果和用户看到的金额对齐后，决定是否继续。
     *
     * <p>骨架已经用 {@link #requireAmountUnchanged} 做了总额比对，够跑通流程。
     * 但它只告诉用户「总价变了」，说不出<b>哪一件</b>变了、从多少变到多少。</p>
     *
     * <p>把它补成逐行比对：入参加上「用户看到的每行单价」，
     * 返回变动明细，让前端能高亮具体商品。做这一步之前先想清楚一件事——
     * 让前端把它看到的单价传上来，会不会给了客户端指定价格的机会？
     * （提示：只用于比对、不用于下单，就不会。但这条边界必须在代码里守住，
     * 一旦有人顺手把这个价格传给了订单服务，就是一个可以任意定价的漏洞。）</p>
     *
     * <p>这一条不影响跑通，属于加分项，没有对应的失败用例。</p>
     */
    @SuppressWarnings("unused")
    private void priceChangeDetail() {
        // TODO(why): 可选。见上方说明。
    }
}
