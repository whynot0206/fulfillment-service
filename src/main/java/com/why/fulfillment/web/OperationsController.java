package com.why.fulfillment.web;

import com.why.fulfillment.observability.OperationsDashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/operations")
public class OperationsController {

    private final OperationsDashboardService dashboardService;

    public OperationsController(OperationsDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/dashboard")
    public ApiResponse<OperationsDashboardService.Snapshot> dashboard() {
        return ApiResponse.ok(dashboardService.snapshot());
    }
}
