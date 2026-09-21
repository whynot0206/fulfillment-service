package com.why.fulfillment.order.service;

import com.why.fulfillment.api.inventory.InventoryClient;
import com.why.fulfillment.api.inventory.InventoryReleaseRequest;
import com.why.fulfillment.api.inventory.InventoryReleaseResponse;
import com.why.fulfillment.api.inventory.InventoryReserveRequest;
import com.why.fulfillment.api.inventory.InventoryReserveResponse;
import com.why.fulfillment.api.inventory.InventoryReserveItem;
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
                orderRepository.updateReservation(normalized.orderId(), ReservationStatus.RESERVED, null);
                return CreateOrderResult.reserved(normalized.orderId(), replayed);
            }
            if (isRejected(response)) {
                String error = response == null ? "inventory rejected without a reason" : response.error();
                orderRepository.updateReservation(normalized.orderId(), ReservationStatus.FAILED, error);
                return CreateOrderResult.failed(normalized.orderId(), error, replayed);
            }
            String error = response == null || response.error() == null || response.error().isBlank()
                    ? "inventory returned an unknown result" : response.error();
            return compensateUnknown(normalized.orderId(), "inventory returned an unknown result: " + error, replayed);
        } catch (FeignException exception) {
            if (isDeterministicClientRejection(exception)) {
                String error = "inventory rejected the request (HTTP " + exception.status() + "): "
                        + safeMessage(exception);
                orderRepository.updateReservation(normalized.orderId(), ReservationStatus.FAILED, error);
                return CreateOrderResult.failed(normalized.orderId(), error, replayed);
            }
            return compensateUnknown(normalized.orderId(), describeRemoteFailure(exception), replayed);
        } catch (RuntimeException exception) {
            return compensateUnknown(normalized.orderId(), "inventory call failed: " + safeMessage(exception), replayed);
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
        try {
            InventoryReleaseResponse response = inventoryClient.release(new InventoryReleaseRequest(orderId));
            if (isReleased(response)) {
                orderRepository.updateReservation(orderId, ReservationStatus.COMPENSATED,
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
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
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
        });
        long distinctSkuCount = command.items().stream().map(OrderItemCommand::skuId).distinct().count();
        if (distinctSkuCount != command.items().size()) {
            throw new IllegalArgumentException("duplicate skuId is not allowed in one order");
        }
    }

    private static boolean fitsMoneyColumn(BigDecimal value) {
        return value.scale() <= 2 && value.precision() - value.scale() <= 10;
    }

    public record CreateOrderCommand(long orderId, long userId, BigDecimal totalAmount, Long timeoutSeconds,
                                     List<OrderItemCommand> items) {
    }

    public record OrderItemCommand(Long skuId, Long spuId, Integer count, BigDecimal price) {
        InventoryReserveItem toInventoryItem() {
            return new InventoryReserveItem(skuId, spuId, count);
        }

        OrderItemRecord toRecord() {
            return new OrderItemRecord(skuId, spuId, count, price);
        }
    }

    public record CreateOrderResult(long orderId, String state, String message, boolean replayed) {
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
