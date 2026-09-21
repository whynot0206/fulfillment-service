package com.why.fulfillment.order.task;

import com.why.fulfillment.order.repository.OrderRepository;
import com.why.fulfillment.order.service.OrderApplicationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderCompensationScheduler {
    private final OrderRepository repository;
    private final OrderApplicationService service;

    public OrderCompensationScheduler(OrderRepository repository, OrderApplicationService service) {
        this.repository = repository;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${fulfillment.compensation.poll-delay-ms:5000}")
    public void retryPending() {
        repository.findPendingCompensationIds(50).forEach(service::retryPendingCompensation);
    }
}
