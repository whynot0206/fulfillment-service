package com.why.fulfillment.commerce.checkout.web;

import com.why.fulfillment.commerce.auth.CurrentUser;
import com.why.fulfillment.commerce.auth.JwtPrincipal;
import com.why.fulfillment.commerce.checkout.dto.CheckoutResultView;
import com.why.fulfillment.commerce.checkout.dto.CheckoutSubmitRequest;
import com.why.fulfillment.commerce.checkout.service.CheckoutService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 结算入口，必须登录。
 *
 * <p><b>只有一个 POST，没有 GET「结算预览」。</b>预览会诱导出一种错误写法：
 * 预览时算一遍金额存起来，提交时用存下来的金额。那等于把「几分钟前的价格」
 * 当成承诺，中间的改价就丢了。要预览就直接读购物车（{@code GET /api/cart}
 * 每次现查价格），金额只在提交这一刻算一次、并且当场用掉。</p>
 *
 * <h2>Idempotency-Key 为什么放在请求头</h2>
 * <p>它不是业务数据，是这一次 HTTP 调用的属性——同一份购物车内容可以被提交两次，
 * 那是两笔不同的结算，键不同而载荷相同。放进 body 会让人误以为它属于载荷，
 * 进而顺手把它算进摘要里，那样摘要就永远不可能相等，幂等直接失效。</p>
 *
 * <h2>为什么键由前端生成</h2>
 * <p>幂等要挡的是「同一次用户操作被发送了多次」——双击、超时重试、断网重连。
 * 只有前端知道这三种情况是同一次操作。服务端生成的话，每个到达的请求都是新的键，
 * 等于没做幂等。前端用 {@code crypto.randomUUID()} 在<b>进入结算页时</b>生成一次，
 * 提交失败重试时复用同一个，下单成功后才换新的。</p>
 */
@RestController
@RequestMapping("/api/checkout")
public class CheckoutController {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final CheckoutService checkoutService;

    public CheckoutController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    /**
     * 提交结算。
     *
     * <p>返回 200 而不是 201：这个接口的结果不止「创建成功」一种。库存不足被拒、
     * 下游返回补偿中，都会带着一个 state 走到这里。用 201 表达它们是错的，
     * 而用 4xx 表达「库存不足」又会让前端分不清「请求有问题」和「请求没问题但没抢到」。
     * 真正的失败（购物车空、价格变动、键冲突）仍然走异常 → 4xx。</p>
     *
     * <p>{@code Idempotency-Key} 缺失时 Spring 会抛 {@code MissingRequestHeaderException}，
     * 由全局异常处理翻成 400——这比给它一个默认值安全：默认值意味着所有没带键的请求
     * 共用同一个键，第二个用户的提交会被当成第一个用户的重放。</p>
     */
    @PostMapping
    public ResponseEntity<CheckoutResultView> submit(@CurrentUser JwtPrincipal principal,
                                                     @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                                     @Valid @RequestBody CheckoutSubmitRequest request) {
        return ResponseEntity.ok(checkoutService.submit(principal.userId(), idempotencyKey, request));
    }
}
