package com.why.fulfillment.inventory.web;

import com.why.fulfillment.api.inventory.InventoryConfirmRequest;
import com.why.fulfillment.api.inventory.InventoryConfirmResponse;
import com.why.fulfillment.api.inventory.InventoryQueryResponse;
import com.why.fulfillment.api.inventory.InventoryReleaseRequest;
import com.why.fulfillment.api.inventory.InventoryReleaseResponse;
import com.why.fulfillment.api.inventory.InventoryReserveRequest;
import com.why.fulfillment.api.inventory.InventoryReserveResponse;
import com.why.fulfillment.inventory.service.InventoryIdempotencyConflictException;
import com.why.fulfillment.inventory.service.InventoryReservationRejectedException;
import com.why.fulfillment.inventory.service.InventoryReservationService;
import com.why.fulfillment.inventory.service.InventorySkuNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** External and service-to-service inventory HTTP endpoints. */
@RestController
@RequestMapping("/internal/inventory")
public class InventoryController {

    private final InventoryReservationService reservationService;

    public InventoryController(InventoryReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping("/reserve")
    public ResponseEntity<InventoryReserveResponse> reserve(
            @RequestBody(required = false) InventoryReserveRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest()
                    .body(InventoryReserveResponse.rejected("request is required"));
        }
        try {
            reservationService.reserve(request.orderId(), request.items());
            return ResponseEntity.ok(InventoryReserveResponse.reserved());
        } catch (InventoryIdempotencyConflictException | InventoryReservationRejectedException exception) {
            return ResponseEntity.ok(InventoryReserveResponse.rejected(exception.getMessage()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(InventoryReserveResponse.rejected(exception.getMessage()));
        } catch (RuntimeException exception) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(InventoryReserveResponse.unknown("inventory operation failed"));
        }
    }

    @PostMapping("/release")
    public ResponseEntity<InventoryReleaseResponse> release(
            @RequestBody(required = false) InventoryReleaseRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest()
                    .body(InventoryReleaseResponse.failed("request is required"));
        }
        try {
            reservationService.release(request.orderId());
            return ResponseEntity.ok(InventoryReleaseResponse.released());
        } catch (InventoryReservationRejectedException exception) {
            return ResponseEntity.ok(InventoryReleaseResponse.failed(exception.getMessage()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(InventoryReleaseResponse.failed(exception.getMessage()));
        } catch (RuntimeException exception) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(InventoryReleaseResponse.failed("inventory operation failed"));
        }
    }

    @PostMapping("/confirm")
    public ResponseEntity<InventoryConfirmResponse> confirm(
            @RequestBody(required = false) InventoryConfirmRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest()
                    .body(InventoryConfirmResponse.failed("request is required"));
        }
        try {
            reservationService.confirm(request.orderId());
            return ResponseEntity.ok(InventoryConfirmResponse.confirmed());
        } catch (InventoryReservationRejectedException exception) {
            return ResponseEntity.ok(InventoryConfirmResponse.failed(exception.getMessage()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(InventoryConfirmResponse.failed(exception.getMessage()));
        } catch (RuntimeException exception) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(InventoryConfirmResponse.failed("inventory operation failed"));
        }
    }

    @GetMapping("/query/{skuId}")
    public ResponseEntity<InventoryQueryResponse> query(@PathVariable Long skuId) {
        try {
            return ResponseEntity.ok(reservationService.query(skuId));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().build();
        } catch (InventorySkuNotFoundException exception) {
            return ResponseEntity.notFound().build();
        } catch (RuntimeException exception) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
