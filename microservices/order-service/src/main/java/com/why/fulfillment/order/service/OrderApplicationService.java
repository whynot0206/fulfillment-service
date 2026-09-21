package com.why.fulfillment.order.service;

import com.why.fulfillment.api.inventory.InventoryClient;
import com.why.fulfillment.api.inventory.InventoryReleaseRequest;
import com.why.fulfillment.api.inventory.InventoryReleaseResponse;
import com.why.fulfillment.api.inventory.InventoryReserveRequest;
import com.why.fulfillment.api.inventory.InventoryReserveResponse;
import com.why.fulfillment.api.inventory.InventoryReserveItem;
import com.why.fulfillment.order.domain.OrderRecord;
import com.why.fulfillment.order.domain.OrderStatus;
import com.why.fulfillment.order.domain.ReservationStatus;
import com.why.fulfillment.order.repository.OrderRepository;
import feign.FeignException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
public class OrderApplicationService {

    private final OrderRepository orderRepository;
    private final InventoryClient inventoryClient;

    public OrderApplicationService(OrderRepository orderRepository, InventoryClient inventoryClient) {
        this.orderRepository = orderRepository;
        this.inventoryClient = inventoryClient;
    }

    public CreateOrderResult createPending(CreateOrderCommand command) {
        validate(command);
        orderRepository.insertPending(command.orderId(), command.userId(), command.totalAmount());

        InventoryReserveRequest request = new InventoryReserveRequest(
                command.orderId(), command.items());
        try {
            InventoryReserveResponse response = inventoryClient.reserve(request);
            if (isReserved(response)) {
                orderRepository.updateReservation(command.orderId(), ReservationStatus.RESERVED, null);
                return CreateOrderResult.reserved(command.orderId());
            }
            if (isRejected(response)) {
                String error = response == null ? "inventory rejected without a reason" : response.error();
                orderRepository.updateReservation(command.orderId(), ReservationStatus.FAILED, error);
                return CreateOrderResult.failed(command.orderId(), error);
            }
            String error = response == null || response.error() == null || response.error().isBlank()
                    ? "inventory returned an unknown result" : response.error();
            return compensateUnknown(command.orderId(), "inventory returned an unknown result: " + error);
        } catch (FeignException exception) {
            if (isDeterministicClientRejection(exception)) {
                String error = "inventory rejected the request (HTTP " + exception.status() + "): "
                        + safeMessage(exception);
                orderRepository.updateReservation(command.orderId(), ReservationStatus.FAILED, error);
                return CreateOrderResult.failed(command.orderId(), error);
            }
            return compensateUnknown(command.orderId(), describeRemoteFailure(exception));
        } catch (RuntimeException exception) {
            return compensateUnknown(command.orderId(), "inventory call failed: " + safeMessage(exception));
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
        try {
            if (orderRepository.markPaidIfPending(orderId, outTradeNo)) {
                return true;
            }
        } catch (DuplicateKeyException conflict) {
            return false;
        }
        } catch (DuplicateKeyException ignored) {
            // The unique trade-number key belongs to another order; that callback is rejected.
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

    private CreateOrderResult compensateUnknown(long orderId, String reason) {
        try {
            InventoryReleaseResponse response = inventoryClient.release(new InventoryReleaseRequest(orderId));
            if (isReleased(response)) {
                orderRepository.updateReservation(orderId, ReservationStatus.COMPENSATED,
                        reason + "; compensation released inventory");
                return CreateOrderResult.compensated(orderId,
                        "inventory result was unknown; reservation was canceled");
            }
            String releaseError = response == null ? "empty compensation response" : response.error();
            orderRepository.markCompensationPending(orderId,
                    reason + "; compensation failed: " + (releaseError == null ? "unknown" : releaseError));
            return CreateOrderResult.pendingCompensation(orderId,
                    "inventory result was unknown; compensation is scheduled");
        } catch (RuntimeException compensationFailure) {
            orderRepository.markCompensationPending(orderId,
                    reason + "; compensation call failed: " + safeMessage(compensationFailure));
            return CreateOrderResult.pendingCompensation(orderId,
                    "inventory result was unknown; compensation is scheduled");
        }
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
                || command.items() == null || command.items().isEmpty()) {
            throw new IllegalArgumentException("positive orderId/userId, non-negative totalAmount and items are required");
        }
        command.items().forEach(item -> {
            if (item == null || item.skuId() == null || item.spuId() == null
                    || item.count() == null || item.count() <= 0) {
                throw new IllegalArgumentException("each item must contain positive skuId, spuId and count");
            }
        });
    }

    public record CreateOrderCommand(long orderId, long userId, BigDecimal totalAmount,
                                     List<InventoryReserveItem> items) {
    }

    public record CreateOrderResult(long orderId, String state, String message) {
        static CreateOrderResult reserved(long orderId) {
            return new CreateOrderResult(orderId, "RESERVED", "inventory reserved");
        }

        static CreateOrderResult failed(long orderId, String message) {
            return new CreateOrderResult(orderId, "FAILED", message);
        }

        static CreateOrderResult compensated(long orderId, String message) {
            return new CreateOrderResult(orderId, "COMPENSATED", message);
        }

        static CreateOrderResult pendingCompensation(long orderId, String message) {
            return new CreateOrderResult(orderId, "PENDING_COMPENSATION", message);
        }
    }
}
