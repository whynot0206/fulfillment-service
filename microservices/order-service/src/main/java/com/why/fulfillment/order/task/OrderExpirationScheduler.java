package com.why.fulfillment.order.task;

import com.why.fulfillment.order.repository.OrderRepository;
import com.why.fulfillment.order.service.OrderApplicationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderExpirationScheduler {

    private final OrderRepository repository;
    private final OrderApplicationService service;

    public OrderExpirationScheduler(OrderRepository repository, OrderApplicationService service) {
        this.repository = repository;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${fulfillment.order-timeout.poll-delay-ms:1000}")
    public void closeExpiredOrders() {
        repository.findExpiredReservedOrderIds(50).forEach(orderId -> {
            if (repository.markExpiredForCompensation(orderId)) {
                service.retryPendingCompensation(orderId);
            }
        });
    }
}
