package com.why.fulfillment.order.service;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import com.why.fulfillment.api.inventory.InventoryClient;
import com.why.fulfillment.api.inventory.InventoryReleaseRequest;
import com.why.fulfillment.api.inventory.InventoryReleaseResponse;
import com.why.fulfillment.api.inventory.InventoryReserveRequest;
import com.why.fulfillment.api.inventory.InventoryReserveResponse;
import com.why.fulfillment.api.inventory.InventoryReserveItem;
import com.why.fulfillment.api.order.OrderCreateRequest;
import com.why.fulfillment.order.domain.OrderRecord;
import com.why.fulfillment.order.domain.OrderItemRecord;
import com.why.fulfillment.order.domain.OrderStatus;
import com.why.fulfillment.order.domain.ReservationStatus;
import com.why.fulfillment.order.repository.OrderRepository;
import feign.FeignException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class OrderApplicationService {

    private static final long DEFAULT_TIMEOUT_SECONDS = 1800;
    private static final long MAX_TIMEOUT_SECONDS = 7 * 24 * 60 * 60;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_OFFSET = 1000;
    /** Matches order_item.name_snapshot VARCHAR(128); see migration-v2-order-snapshot.sql. */
    private static final int MAX_NAME_SNAPSHOT_LENGTH = 128;
    /** Matches order_item.spec_snapshot VARCHAR(500). */
    private static final int MAX_SPEC_SNAPSHOT_LENGTH = 500;

    private final OrderRepository orderRepository;
    private final InventoryClient inventoryClient;

    public OrderApplicationService(OrderRepository orderRepository, InventoryClient inventoryClient) {
        this.orderRepository = orderRepository;
        this.inventoryClient = inventoryClient;
    }

    public CreateOrderResult createPending(CreateOrderCommand command) {
        validate(command);
        CreateOrderCommand normalized = normalize(command);
        boolean replayed = false;
        try {
            orderRepository.insertPending(normalized.orderId(), normalized.userId(), normalized.totalAmount(),
                    normalized.timeoutSeconds(),
                    normalized.items().stream().map(OrderItemCommand::toRecord).toList());
        } catch (DuplicateKeyException duplicate) {
            OrderRecord existing = orderRepository.find(normalized.orderId()).orElseThrow(() -> duplicate);
            if (!samePayload(existing, normalized)) {
                return CreateOrderResult.conflict(normalized.orderId(),
                        "orderId already exists with a different request payload");
            }
            CreateOrderResult terminal = resultForExisting(existing);
            if (terminal != null) {
                return terminal;
            }
            replayed = true;
        }

        InventoryReserveRequest request = new InventoryReserveRequest(
                normalized.orderId(), normalized.items().stream().map(OrderItemCommand::toInventoryItem).toList());
        try {
            InventoryReserveResponse response = inventoryClient.reserve(request);
            if (isReserved(response)) {
                if (!orderRepository.updateReservation(normalized.orderId(), ReservationStatus.RESERVED, null)) {
                    // A concurrent identical request may have marked RESERVED first. Releasing
                    // in that case would destroy a valid order, so inspect durable state.
                    OrderRecord current = orderRepository.find(normalized.orderId())
                            .orElseThrow(() -> new IllegalStateException("order disappeared after reservation"));
                    if (current.status() == OrderStatus.CANCELED
                            && current.reservationStatus() == ReservationStatus.PENDING_COMPENSATION) {
                        retryPendingCompensation(normalized.orderId());
                    }
                    CreateOrderResult settled = resultForExisting(current);
                    return settled == null
                            ? CreateOrderResult.pendingCompensation(normalized.orderId(),
                                    "reservation result is still being settled", replayed)
                            : settled;
                }
                return CreateOrderResult.reserved(normalized.orderId(), replayed);
            }
            if (isRejected(response)) {
                String error = response == null ? "inventory rejected without a reason" : response.error();
                if (!orderRepository.updateReservation(normalized.orderId(), ReservationStatus.FAILED, error)) {
                    OrderRecord current = orderRepository.find(normalized.orderId())
                            .orElseThrow(() -> new IllegalStateException("order disappeared after rejection"));
                    CreateOrderResult settled = resultForExisting(current);
                    return settled == null
                            ? CreateOrderResult.pendingCompensation(normalized.orderId(),
                                    "reservation result is being settled", replayed)
                            : settled;
                }
                return CreateOrderResult.failed(normalized.orderId(), error, replayed);
            }
            String error = response == null || response.error() == null || response.error().isBlank()
                    ? "inventory returned an unknown result" : response.error();
            return compensateUnknown(normalized.orderId(), "inventory returned an unknown result: " + error, replayed);
        } catch (FeignException exception) {
            if (isDeterministicClientRejection(exception)) {
                String error = "inventory rejected the request (HTTP " + exception.status() + "): "
                        + safeMessage(exception);
                if (!orderRepository.updateReservation(normalized.orderId(), ReservationStatus.FAILED, error)) {
                    return resultAfterLostDecision(normalized.orderId(), replayed);
                }
                return CreateOrderResult.failed(normalized.orderId(), error, replayed);
            }
            return compensateUnknown(normalized.orderId(), describeRemoteFailure(exception), replayed);
        } catch (RuntimeException exception) {
            return compensateUnknown(normalized.orderId(), "inventory call failed: " + safeMessage(exception), replayed);
        }
    }

    /**
     * Creates an order on behalf of Commerce checkout, carrying the price snapshot.
     *
     * <p>It converges on {@link #createPending} so the idempotency, reservation and
     * compensation behaviour is literally the same code as the public entry point. The only
     * thing this method adds is a check the public endpoint does not have: the declared total
     * must equal the sum of the lines.</p>
     *
     * <p>That check is not paranoia about Commerce being buggy — it is about which number the
     * user was shown. Commerce computed a total, rendered it, and the user agreed to it. If
     * that total disagrees with the lines, one of the two is wrong and we do not know which,
     * so charging the recomputed sum would mean charging a number nobody saw. Rejecting is the
     * only answer that cannot silently overcharge.</p>
     */
    public CreateOrderResult createFromCommerce(OrderCreateRequest request) {
        return createPending(commerceCommand(request));
    }

    /**
     * Resolve a saved Commerce snapshot without creating an order or calling Inventory.
     * Main-order terminal states take precedence over an old reservation snapshot, while
     * outstanding compensation remains explicit so the caller can wait for convergence.
     */
    public CreateOrderResult resolveCreateFromCommerce(OrderCreateRequest request) {
        CreateOrderCommand command = commerceCommand(request);
        validate(command);
        CreateOrderCommand normalized = normalize(command);
        OrderRecord existing = orderRepository.find(normalized.orderId()).orElse(null);
        if (existing == null) {
            return new CreateOrderResult(normalized.orderId(), "NOT_FOUND", "order is not present", false);
        }
        if (!samePayload(existing, normalized)) {
            return CreateOrderResult.conflict(normalized.orderId(),
                    "orderId already exists with a different request payload");
        }
        if (existing.status() == OrderStatus.PAID) {
            return new CreateOrderResult(existing.orderId(), "PAID", "order is paid", true);
        }
        if (existing.reservationStatus() == ReservationStatus.PENDING_COMPENSATION) {
            return CreateOrderResult.pendingCompensation(existing.orderId(),
                    "existing request is waiting for compensation", true);
        }
        if (existing.status() == OrderStatus.CLOSED) {
            return new CreateOrderResult(existing.orderId(), "CLOSED", "order is closed", true);
        }
        if (existing.status() == OrderStatus.CANCELED
                && existing.reservationStatus() != ReservationStatus.FAILED
                && existing.reservationStatus() != ReservationStatus.COMPENSATED) {
            return new CreateOrderResult(existing.orderId(), "CANCELED", "order is canceled", true);
        }
        CreateOrderResult settled = resultForExisting(existing);
        return settled == null
                ? new CreateOrderResult(existing.orderId(), "RESERVING", "reservation is unresolved", true)
                : settled;
    }

    private static CreateOrderCommand commerceCommand(OrderCreateRequest request) {
        if (request == null || request.orderId() == null || request.userId() == null
                || request.items() == null || request.items().isEmpty()) {
            throw new IllegalArgumentException("orderId, userId and at least one item are required");
        }
        List<OrderItemCommand> items = request.items().stream()
                .map(item -> {
                    if (item == null) {
                        throw new IllegalArgumentException("each item is required");
                    }
                    return new OrderItemCommand(item.skuId(), item.spuId(), item.count(), item.price(),
                            item.nameSnapshot(), item.specSnapshot());
                })
                .toList();
        requireTotalMatchesItems(request.totalAmount(), items);
        return new CreateOrderCommand(request.orderId(), request.userId(),
                request.totalAmount(), request.timeoutSeconds(), items);
    }

    private static void requireTotalMatchesItems(BigDecimal declaredTotal, List<OrderItemCommand> items) {
        if (declaredTotal == null) {
            throw new IllegalArgumentException("totalAmount is required");
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (OrderItemCommand item : items) {
            if (item == null || item.price() == null || item.count() == null) {
                // Leave the detailed per-item complaint to validate(); here we only need to
                // avoid a NullPointerException while adding things up.
                throw new IllegalArgumentException("each item must contain a count and a price");
            }
            sum = sum.add(item.price().multiply(BigDecimal.valueOf(item.count())));
        }
        // compareTo, not equals: 10.0 and 10.00 are the same amount of money but different
        // BigDecimal values. equals() here would reject correct requests over trailing zeros.
        if (sum.compareTo(declaredTotal) != 0) {
            throw new IllegalArgumentException(
                    "totalAmount " + declaredTotal.toPlainString() + " does not match the sum of items "
                            + sum.toPlainString());
        }
    }

    /**
     * Internal payment transition. It returns true for a repeated callback that already applied the
     * same trade number, allowing payment retries to stop without changing order state again.
     */
    public boolean markPaid(Long orderId, String outTradeNo) {
        if (orderId == null || orderId <= 0 || outTradeNo == null || outTradeNo.isBlank()) {
            throw new IllegalArgumentException("positive orderId and outTradeNo are required");
        }
        try {
            if (orderRepository.markPaidIfPending(orderId, outTradeNo)) {
                return true;
            }
        } catch (DuplicateKeyException conflict) {
            return false;
        }
        Optional<OrderRecord> existing = orderRepository.find(orderId);
        return existing.isPresent()
                && existing.get().status() == OrderStatus.PAID
                && outTradeNo.equals(existing.get().outTradeNo());
    }

    public Optional<OrderRecord> find(long orderId) {
        return orderRepository.find(orderId);
    }

    /**
     * Order detail, scoped to its owner.
     *
     * <p>Returns empty both when the order does not exist and when it belongs to somebody
     * else. The caller turns both into 404. A 403 for the second case would be a working
     * order-id oracle: an attacker enumerating ids could tell which ones exist from the status
     * code alone, and order ids are far from unguessable.</p>
     */
    public Optional<OrderRecord> findOwned(long orderId, long userId) {
        return orderRepository.find(orderId).filter(order -> order.userId() == userId);
    }

    public CancelOrderResult cancelOwned(long orderId, long userId) {
        if (orderId <= 0 || userId <= 0) {
            throw new IllegalArgumentException("positive orderId and userId are required");
        }
        OrderRecord current = findOwned(orderId, userId).orElse(null);
        if (current == null) {
            return new CancelOrderResult(orderId, "NOT_FOUND");
        }
        if (current.status() == OrderStatus.CANCELED) {
            return new CancelOrderResult(orderId, "CANCELED");
        }
        if (current.status() != OrderStatus.PENDING_PAYMENT) {
            return new CancelOrderResult(orderId, "CONFLICT");
        }
        if (orderRepository.markUserCanceledForCompensation(orderId, userId)) {
            retryPendingCompensation(orderId);
            return new CancelOrderResult(orderId, "CANCELED");
        }
        // A payment callback or expiry task may have won the conditional update.
        OrderRecord latest = findOwned(orderId, userId).orElse(null);
        return new CancelOrderResult(orderId,
                latest != null && latest.status() == OrderStatus.CANCELED ? "CANCELED" : "CONFLICT");
    }

    /**
     * One page of the caller's own orders.
     *
     * <p>{@code page} is zero based. The offset is capped rather than left open: OFFSET makes
     * MySQL walk and throw away every skipped row, so an unbounded page number is a cheap way
     * for one request to scan a user's entire order history. Nobody browses to page 500 of
     * their own orders; a crawler does.</p>
     */
    public OrderPage findForUser(long userId, int page, int size) {
        if (userId <= 0) {
            throw new IllegalArgumentException("a positive userId is required");
        }
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        int offset = safePage * safeSize;
        if (offset > MAX_OFFSET) {
            throw new IllegalArgumentException(
                    "page is too deep; at most " + MAX_OFFSET + " orders can be skipped");
        }
        return new OrderPage(orderRepository.findByUser(userId, safeSize, offset),
                orderRepository.countByUser(userId), safePage, safeSize);
    }

    /**
     * Expire an unresolved ordinary reservation, not a payment deadline. The repository
     * transaction decides cancellation first; no transaction is kept open across Feign.
     * A crash after that commit leaves a PENDING_COMPENSATION row for the existing worker.
     */
    public boolean recoverStaleReservation(long orderId, long graceSeconds) {
        if (orderId <= 0) {
            throw new IllegalArgumentException("positive orderId is required");
        }
        if (!orderRepository.markStaleReservingForCompensation(orderId, graceSeconds)) {
            return false;
        }
        retryPendingCompensation(orderId);
        return true;
    }

    public void retryPendingCompensation(long orderId) {
        try {
            InventoryReleaseResponse response = inventoryClient.release(new InventoryReleaseRequest(orderId));
            if (isReleased(response)) {
                orderRepository.markCompensatedIfPending(orderId, "background compensation released inventory");
                return;
            }
            String error = response == null ? "empty compensation response" : response.error();
            orderRepository.markCompensationPending(orderId,
                    "background compensation failed: " + (error == null ? "unknown" : error));
        } catch (RuntimeException exception) {
            orderRepository.markCompensationPending(orderId,
                    "background compensation call failed: " + safeMessage(exception));
        }
    }

    private CreateOrderResult compensateUnknown(long orderId, String reason, boolean replayed) {
        // A duplicate request may already have committed RESERVED (or even PAID).
        // Only a caller that first wins the cancellation CAS may initiate release.
        if (!orderRepository.markReservingForCompensation(orderId, reason)) {
            return resultAfterLostDecision(orderId, replayed);
        }
        try {
            InventoryReleaseResponse response = inventoryClient.release(new InventoryReleaseRequest(orderId));
            if (isReleased(response)) {
                orderRepository.markCompensatedIfPending(orderId,
                        reason + "; compensation released inventory");
                return CreateOrderResult.compensated(orderId,
                        "inventory result was unknown; reservation was canceled", replayed);
            }
            String releaseError = response == null ? "empty compensation response" : response.error();
            orderRepository.markCompensationPending(orderId,
                    reason + "; compensation failed: " + (releaseError == null ? "unknown" : releaseError));
            return CreateOrderResult.pendingCompensation(orderId,
                    "inventory result was unknown; compensation is scheduled", replayed);
        } catch (RuntimeException compensationFailure) {
            orderRepository.markCompensationPending(orderId,
                    reason + "; compensation call failed: " + safeMessage(compensationFailure));
            return CreateOrderResult.pendingCompensation(orderId,
                    "inventory result was unknown; compensation is scheduled", replayed);
        }
    }

    private CreateOrderResult resultAfterLostDecision(long orderId, boolean replayed) {
        OrderRecord current = orderRepository.find(orderId)
                .orElseThrow(() -> new IllegalStateException("order disappeared while settling reservation"));
        CreateOrderResult settled = resultForExisting(current);
        return settled == null
                ? CreateOrderResult.pendingCompensation(orderId, "reservation result is still being settled", replayed)
                : settled;
    }

    private static CreateOrderCommand normalize(CreateOrderCommand command) {
        List<OrderItemCommand> items = command.items().stream()
                .sorted(Comparator.comparing(OrderItemCommand::skuId))
                .toList();
        long timeoutSeconds = command.timeoutSeconds() == null
                ? DEFAULT_TIMEOUT_SECONDS : command.timeoutSeconds();
        return new CreateOrderCommand(command.orderId(), command.userId(), command.totalAmount(),
                timeoutSeconds, items);
    }

    private static boolean samePayload(OrderRecord existing, CreateOrderCommand command) {
        if (existing.userId() != command.userId()
                || existing.totalAmount().compareTo(command.totalAmount()) != 0
                || !existing.timeoutSeconds().equals(command.timeoutSeconds())
                || existing.items().size() != command.items().size()) {
            return false;
        }
        for (int index = 0; index < command.items().size(); index++) {
            OrderItemRecord stored = existing.items().get(index);
            OrderItemCommand requested = command.items().get(index);
            // nameSnapshot / specSnapshot are deliberately NOT compared.
            //
            // They are display text, and they are read from the product service at the moment
            // the request is built. A retry that happens after the merchant renamed the product
            // would carry different wording for the same order — comparing it would turn a
            // legitimate, safe replay into a 409 and leave the caller with an order it cannot
            // finish and cannot recreate. What must match is what the two sides agreed on:
            // who, how much, which skus, what price.
            if (!stored.skuId().equals(requested.skuId())
                    || !stored.spuId().equals(requested.spuId())
                    || !stored.count().equals(requested.count())
                    || stored.price().compareTo(requested.price()) != 0) {
                return false;
            }
        }
        return true;
    }

    private static CreateOrderResult resultForExisting(OrderRecord existing) {
        return switch (existing.reservationStatus()) {
            case RESERVED -> CreateOrderResult.reserved(existing.orderId(), true);
            case FAILED -> CreateOrderResult.failed(existing.orderId(), existing.reservationError(), true);
            case COMPENSATED -> CreateOrderResult.compensated(existing.orderId(),
                    "existing request was already compensated", true);
            case PENDING_COMPENSATION -> CreateOrderResult.pendingCompensation(existing.orderId(),
                    "existing request is waiting for compensation", true);
            case RESERVING -> null;
        };
    }

    private static boolean isReserved(InventoryReserveResponse response) {
        return response != null && "RESERVED".equalsIgnoreCase(response.status());
    }

    private static boolean isRejected(InventoryReserveResponse response) {
        return response != null && ("REJECTED".equalsIgnoreCase(response.status())
                || "FAILED".equalsIgnoreCase(response.status()));
    }

    private static boolean isReleased(InventoryReleaseResponse response) {
        return response != null && ("RELEASED".equalsIgnoreCase(response.status())
                || "COMPENSATED".equalsIgnoreCase(response.status()));
    }

    private static String describeRemoteFailure(FeignException exception) {
        return "inventory remote failure (HTTP " + exception.status() + "): " + safeMessage(exception);
    }

    private static boolean isDeterministicClientRejection(FeignException exception) {
        int status = exception.status();
        return status >= 400 && status < 500 && status != 408 && status != 429;
    }

    private static String safeMessage(Throwable exception) {
        // These summaries are persisted and exposed in order details. Feign messages
        // can contain downstream URLs, response bodies and credentials; never store them.
        String type = exception.getClass().getSimpleName();
        if (type.isBlank()) {
            type = "RuntimeException";
        }
        return exception instanceof FeignException remote
                ? type + " (HTTP " + remote.status() + ")" : type;
    }

    private static void validate(CreateOrderCommand command) {
        if (command == null || command.orderId() <= 0 || command.userId() <= 0
                || command.totalAmount() == null || command.totalAmount().signum() < 0
                || !fitsMoneyColumn(command.totalAmount())
                || (command.timeoutSeconds() != null && (command.timeoutSeconds() <= 0
                    || command.timeoutSeconds() > MAX_TIMEOUT_SECONDS))
                || command.items() == null || command.items().isEmpty()) {
            throw new IllegalArgumentException(
                    "positive orderId/userId, DECIMAL(12,2) totalAmount, valid timeoutSeconds and items are required");
        }
        command.items().forEach(item -> {
            if (item == null || item.skuId() == null || item.spuId() == null
                    || item.count() == null || item.count() <= 0 || item.price() == null
                    || item.price().signum() < 0 || !fitsMoneyColumn(item.price())) {
                throw new IllegalArgumentException(
                        "each item must contain positive skuId/spuId/count and a DECIMAL(12,2) price");
            }
            // Reject rather than truncate. Truncating would write a product name that is not
            // the product's name and nobody would ever find out; a rejected request is loud.
            requireFits(item.nameSnapshot(), MAX_NAME_SNAPSHOT_LENGTH, "nameSnapshot");
            requireFits(item.specSnapshot(), MAX_SPEC_SNAPSHOT_LENGTH, "specSnapshot");
        });
        long distinctSkuCount = command.items().stream().map(OrderItemCommand::skuId).distinct().count();
        if (distinctSkuCount != command.items().size()) {
            throw new IllegalArgumentException("duplicate skuId is not allowed in one order");
        }
    }

    private static boolean fitsMoneyColumn(BigDecimal value) {
        return value.scale() <= 2 && value.precision() - value.scale() <= 10;
    }

    private static void requireFits(String value, int maxLength, String field) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be at most " + maxLength + " characters");
        }
    }

    /**
     * A page of orders plus the total count, so the caller can render "第 N 页 / 共 M 条"
     * without a second request.
     */
    public record OrderPage(List<OrderRecord> orders, long total, int page, int size) {
    }

    public record CancelOrderResult(@JsonSerialize(using = ToStringSerializer.class) long orderId, String state) {
    }

    public record CreateOrderCommand(long orderId, long userId, BigDecimal totalAmount, Long timeoutSeconds,
                                     List<OrderItemCommand> items) {
    }

    public record OrderItemCommand(Long skuId, Long spuId, Integer count, BigDecimal price,
                                   String nameSnapshot, String specSnapshot) {

        public OrderItemCommand(Long skuId, Long spuId, Integer count, BigDecimal price) {
            this(skuId, spuId, count, price, null, null);
        }

        InventoryReserveItem toInventoryItem() {
            return new InventoryReserveItem(skuId, spuId, count);
        }

        OrderItemRecord toRecord() {
            return new OrderItemRecord(skuId, spuId, count, price, nameSnapshot, specSnapshot);
        }
    }

    public record CreateOrderResult(@JsonSerialize(using = ToStringSerializer.class) long orderId,
                                    String state, String message, boolean replayed) {
        static CreateOrderResult reserved(long orderId, boolean replayed) {
            return new CreateOrderResult(orderId, "RESERVED", "inventory reserved", replayed);
        }

        static CreateOrderResult failed(long orderId, String message, boolean replayed) {
            return new CreateOrderResult(orderId, "FAILED", message, replayed);
        }

        static CreateOrderResult compensated(long orderId, String message, boolean replayed) {
            return new CreateOrderResult(orderId, "COMPENSATED", message, replayed);
        }

        static CreateOrderResult pendingCompensation(long orderId, String message, boolean replayed) {
            return new CreateOrderResult(orderId, "PENDING_COMPENSATION", message, replayed);
        }

        static CreateOrderResult conflict(long orderId, String message) {
            return new CreateOrderResult(orderId, "CONFLICT", message, true);
        }
    }
}
