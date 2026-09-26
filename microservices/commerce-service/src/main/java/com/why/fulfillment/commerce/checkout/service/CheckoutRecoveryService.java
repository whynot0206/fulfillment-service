package com.why.fulfillment.commerce.checkout.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.why.fulfillment.api.order.OrderClient;
import com.why.fulfillment.api.order.OrderCreateRequest;
import com.why.fulfillment.api.order.OrderCreateResponse;
import com.why.fulfillment.commerce.checkout.dto.CheckoutResultView;
import com.why.fulfillment.commerce.checkout.entity.CheckoutRequest;
import com.why.fulfillment.commerce.checkout.mapper.CheckoutRequestMapper;
import com.why.fulfillment.commerce.common.CommerceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.why.fulfillment.commerce.checkout.entity.CheckoutRequest.*;

/**
 * Durable intent recovery, not a cross-service transaction. Every remote attempt uses the stored
 * snapshot/id. Once the creation window closes, only the read-only resolve API may be called.
 */
@Service
public class CheckoutRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(CheckoutRecoveryService.class);
    private static final Set<String> REJECTED = Set.of("FAILED", "COMPENSATED", "CANCELED", "CLOSED");
    private final CheckoutRequestMapper mapper;
    private final OrderClient orderClient;
    private final ObjectMapper json;
    private final long leaseSeconds;
    private final int maxAttempts;
    private final long baseBackoffSeconds;
    private final long maxBackoffSeconds;
    private final long maxWindowSeconds;
    private final long manualRecheckSeconds;

    public CheckoutRecoveryService(CheckoutRequestMapper mapper, OrderClient orderClient, ObjectMapper json,
            @Value("${commerce.checkout.recovery.lease-seconds:30}") long leaseSeconds,
            @Value("${commerce.checkout.recovery.max-attempts:8}") int maxAttempts,
            @Value("${commerce.checkout.recovery.base-backoff-seconds:2}") long baseBackoffSeconds,
            @Value("${commerce.checkout.recovery.max-backoff-seconds:60}") long maxBackoffSeconds,
            @Value("${commerce.checkout.recovery.max-window-seconds:1800}") long maxWindowSeconds,
            @Value("${commerce.checkout.recovery.manual-recheck-seconds:30}") long manualRecheckSeconds) {
        if (leaseSeconds <= 0 || maxAttempts <= 0 || baseBackoffSeconds <= 0
                || maxBackoffSeconds < baseBackoffSeconds || maxWindowSeconds <= 0 || manualRecheckSeconds <= 0) {
            throw new IllegalArgumentException("Checkout recovery limits must be positive");
        }
        this.mapper = mapper;
        this.orderClient = orderClient;
        this.json = json;
        this.leaseSeconds = leaseSeconds;
        this.maxAttempts = maxAttempts;
        this.baseBackoffSeconds = baseBackoffSeconds;
        this.maxBackoffSeconds = maxBackoffSeconds;
        this.maxWindowSeconds = maxWindowSeconds;
        this.manualRecheckSeconds = manualRecheckSeconds;
    }

    CheckoutRequest prepare(long userId, String key, String digest, OrderCreateRequest request) {
        if (request.orderId() == null || request.timeoutSeconds() == null || request.timeoutSeconds() <= 0) {
            throw new IllegalArgumentException("Checkout intent requires an id and a positive timeout");
        }
        CheckoutRequest intent = new CheckoutRequest();
        intent.setUserId(userId);
        intent.setIdempotencyKey(key);
        intent.setRequestDigest(digest);
        intent.setOrderId(request.orderId());
        intent.setTotalAmount(request.totalAmount());
        try {
            intent.setRequestPayload(json.writeValueAsString(request));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize checkout intent", exception);
        }
        intent.setStatus(STATUS_IN_PROGRESS);
        intent.setRecoveryState(RECOVERY_ACTIVE);
        intent.setAttemptCount(1);
        intent.setLeaseOwner(UUID.randomUUID().toString());
        intent.setLeaseSeconds(leaseSeconds);
        intent.setRecoveryWindowSeconds(Math.min(request.timeoutSeconds(), maxWindowSeconds));
        return intent;
    }

    record InitialResult(CheckoutResultView view, boolean cleanupEligible) {}
    private record OwnedResult(CheckoutRequest intent, boolean initialSuccess) {}

    InitialResult executeInitial(CheckoutRequest intent) {
        OwnedResult resolved = executeOwned(intent.getId(), intent.getLeaseOwner(), false, false);
        return new InitialResult(resultOf(resolved.intent(), false), resolved.initialSuccess());
    }

    /** Same-key manual review is rate-limited, read-only and can never create an order. */
    CheckoutResultView replay(CheckoutRequest existing) {
        if (isPending(existing) && hasSnapshot(existing)
                && Integer.valueOf(RECOVERY_MANUAL).equals(existing.getRecoveryState())) {
            String owner = UUID.randomUUID().toString();
            if (mapper.tryClaim(existing.getId(), owner, leaseSeconds, RECOVERY_MANUAL) == 1) {
                existing = executeOwned(existing.getId(), owner, true, true).intent();
            } else {
                existing = mapper.selectById(existing.getId());
            }
        }
        return resultOf(existing, true);
    }

    /** Called by the scheduler. A CAS lease protects both execution and its completion. */
    public void recover(long id) {
        String owner = UUID.randomUUID().toString();
        if (mapper.tryClaim(id, owner, leaseSeconds, RECOVERY_ACTIVE) == 1) {
            executeOwned(id, owner, true, false);
        }
    }

    private OwnedResult executeOwned(long id, String owner, boolean recovered, boolean reviewOnly) {
        CheckoutRequest intent = mapper.selectOwned(id, owner);
        if (intent == null) {
            return new OwnedResult(mapper.selectById(id), false);
        }
        OrderCreateRequest snapshot;
        try {
            snapshot = readSnapshot(intent);
        } catch (IllegalArgumentException exception) {
            defer(intent, owner, true, "结算快照缺失或不一致，需要人工核对");
            return new OwnedResult(mapper.selectById(id), false);
        }
        boolean attemptsExhausted = intent.getAttemptCount() != null && intent.getAttemptCount() > maxAttempts;
        OrderCreateResponse response;
        try {
            // Before any replayed create, resolve the original intent without changing Order/Inventory.
            boolean mayCreate = !reviewOnly && !attemptsExhausted && mapper.creationAllowed(id, owner) == 1;
            if (recovered || !mayCreate) {
                response = orderClient.resolveCreate(snapshot);
                if (response != null && "NOT_FOUND".equals(response.state()) && mayCreate
                        && mapper.creationAllowed(id, owner) == 1) {
                    response = orderClient.create(snapshot);
                }
            } else {
                response = orderClient.create(snapshot);
            }
        } catch (RuntimeException exception) {
            // Never persist exception text: downstream exception bodies can contain sensitive content.
            log.warn("Checkout remote result unknown, intent {} order {} owner {} ({})",
                    id, intent.getOrderId(), owner, exception.getClass().getSimpleName());
            defer(intent, owner, reviewOnly || attemptsExhausted, "订单结果暂未确认，请保留原提交凭证");
            return new OwnedResult(mapper.selectById(id), false);
        }
        boolean initialSuccess = false;
        if (response != null && !Objects.equals(response.orderId(), intent.getOrderId())) {
            defer(intent, owner, true, "订单结果与原结算不一致，需要人工核对");
        } else if (response != null && ("RESERVED".equals(response.state()) || "PAID".equals(response.state()))) {
            // Persist conservatively before cart cleanup. A crash or a later worker must not
            // imply cleanup succeeded; only the original unexpired owner may attempt the CAS.
            int completed = mapper.finishSubmitted(id, owner, response.state(), true);
            initialSuccess = completed == 1 && !recovered;
        } else if (response != null && response.state() != null && REJECTED.contains(response.state())) {
            mapper.finishRejected(id, owner, response.state(), safeMessage(response));
        } else {
            boolean manual = reviewOnly || attemptsExhausted
                    || (response != null && ("NOT_FOUND".equals(response.state()) || "CONFLICT".equals(response.state())));
            // NOT_FOUND after the bounded create window is NOT proof that an older create cannot arrive.
            defer(intent, owner, manual, manual
                    ? "原结算尚不能安全确认，需要人工核对；请保留原提交凭证"
                    : "订单仍在处理或补偿，请稍后使用原提交凭证重试");
        }
        // Even a late successful response must be discarded after lease loss; always use durable state.
        return new OwnedResult(mapper.selectById(id), initialSuccess);
    }

    private OrderCreateRequest readSnapshot(CheckoutRequest intent) {
        if (!hasSnapshot(intent)) {
            throw new IllegalArgumentException("Missing original intent");
        }
        try {
            OrderCreateRequest request = json.readValue(intent.getRequestPayload(), OrderCreateRequest.class);
            if (request == null || !Objects.equals(request.orderId(), intent.getOrderId())
                    || !Objects.equals(request.userId(), intent.getUserId())
                    || request.totalAmount() == null || intent.getTotalAmount() == null
                    || request.totalAmount().compareTo(intent.getTotalAmount()) != 0
                    || request.timeoutSeconds() == null || request.timeoutSeconds() <= 0
                    || request.items() == null || request.items().isEmpty()) {
                throw new IllegalArgumentException("Inconsistent intent");
            }
            return request;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid intent", exception);
        }
    }

    private void defer(CheckoutRequest intent, String owner, boolean manual, String message) {
        int attempts = intent.getAttemptCount() == null ? maxAttempts : intent.getAttemptCount();
        manual = manual || attempts >= maxAttempts;
        int state = manual ? RECOVERY_MANUAL : RECOVERY_ACTIVE;
        if (mapper.deferOwned(intent.getId(), owner, message, state,
                manual ? manualRecheckSeconds : backoffSeconds(attempts)) == 1 && manual) {
            log.warn("Checkout requires manual verification, intent {} order {}", intent.getId(), intent.getOrderId());
        }
    }

    long backoffSeconds(int attempts) {
        long delay = baseBackoffSeconds;
        for (int i = 1; i < attempts && delay < maxBackoffSeconds; i++) {
            delay = delay > maxBackoffSeconds / 2 ? maxBackoffSeconds : delay * 2;
        }
        return Math.min(delay, maxBackoffSeconds);
    }

    private CheckoutResultView resultOf(CheckoutRequest intent, boolean replayed) {
        if (intent == null) {
            throw CommerceException.conflict("CHECKOUT_RESULT_UNKNOWN", "结算结果暂未确认，请保留原提交凭证");
        }
        if (Integer.valueOf(STATUS_SUBMITTED).equals(intent.getStatus())) {
            boolean cleanup = Boolean.TRUE.equals(intent.getCartCleanupRequired());
            // RESERVED is the existing browser's 'accepted checkout' signal, not current Order status.
            String message = cleanup ? "原结算已确认，请查看订单当前状态；购物车有后续变更或清理未完成，请核对后处理，避免重复下单"
                    : "原结算已确认，请查看订单当前状态";
            return new CheckoutResultView(intent.getOrderId(), "RESERVED", message,
                    intent.getTotalAmount(), replayed, cleanup);
        }
        if (Integer.valueOf(STATUS_REJECTED).equals(intent.getStatus())) {
            return new CheckoutResultView(intent.getOrderId(),
                    intent.getResultState() == null ? "FAILED" : intent.getResultState(),
                    intent.getLastError() == null ? "原结算已结束，请查看订单当前状态" : intent.getLastError(),
                    intent.getTotalAmount(), replayed, false);
        }
        if (!hasSnapshot(intent) || Integer.valueOf(RECOVERY_MANUAL).equals(intent.getRecoveryState())) {
            throw CommerceException.conflict("CHECKOUT_RECOVERY_REQUIRED",
                    "原结算需要人工核对，请保留原提交凭证，不要重新下单");
        }
        throw CommerceException.conflict(replayed ? "CHECKOUT_IN_PROGRESS" : "CHECKOUT_RESULT_UNKNOWN",
                "原结算结果暂未确认，系统会核对原订单；请保留原提交凭证");
    }

    private static boolean isPending(CheckoutRequest intent) {
        return Integer.valueOf(STATUS_IN_PROGRESS).equals(intent.getStatus());
    }

    private static boolean hasSnapshot(CheckoutRequest intent) {
        return intent.getOrderId() != null && intent.getRequestPayload() != null && !intent.getRequestPayload().isBlank();
    }

    private static String safeMessage(OrderCreateResponse response) {
        return switch (response.state()) {
            case "CANCELED", "CLOSED" -> "原订单已关闭，请查看订单当前状态";
            case "COMPENSATED" -> "原订单已完成取消补偿";
            default -> "库存不足或订单创建被拒绝";
        };
    }
}
