package com.why.fulfillment.inventory.web;

import com.why.fulfillment.api.inventory.InventoryConfirmRequest;
import com.why.fulfillment.api.inventory.InventoryConfirmResponse;
import com.why.fulfillment.api.inventory.InventoryQueryResponse;
import com.why.fulfillment.api.inventory.InventoryReleaseRequest;
import com.why.fulfillment.api.inventory.InventoryReleaseResponse;
import com.why.fulfillment.api.inventory.InventoryReserveRequest;
import com.why.fulfillment.api.inventory.InventoryReserveResponse;
import com.why.fulfillment.api.inventory.InventoryRedisReserveRequest;
import com.why.fulfillment.api.inventory.InventoryRedisReserveResponse;
import com.why.fulfillment.api.inventory.InventoryRedisCompensateRequest;
import com.why.fulfillment.api.inventory.InventoryRedisCompensateResponse;
import com.why.fulfillment.inventory.redis.RedisStockResult;
import com.why.fulfillment.inventory.redis.RedisStockResultStatus;
import com.why.fulfillment.inventory.redis.RedisStockService;
import com.why.fulfillment.inventory.service.InventoryIdempotencyConflictException;
import com.why.fulfillment.inventory.service.InventoryReservationRejectedException;
import com.why.fulfillment.inventory.service.InventoryReservationService;
import com.why.fulfillment.inventory.service.InventorySkuNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final RedisStockService redisInventoryService;

    public InventoryController(InventoryReservationService reservationService) {
        this(reservationService, null);
    }

    @Autowired
    public InventoryController(InventoryReservationService reservationService,
                               RedisStockService redisInventoryService) {
        this.reservationService = reservationService;
        this.redisInventoryService = redisInventoryService;
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
            var items = reservationService.reservationItems(request.orderId());
            reservationService.release(request.orderId());
            if (redisInventoryService != null && !items.isEmpty()) {
                RedisStockResult redisResult =
                        redisInventoryService.compensateIfReserved(request.orderId(), items);
                if (!(redisResult.status() == RedisStockResultStatus.COMPENSATED
                        || redisResult.status() == RedisStockResultStatus.ALREADY_COMPENSATED
                        || redisResult.status() == RedisStockResultStatus.NO_RESERVATION)) {
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(InventoryReleaseResponse.failed("Redis inventory release is pending"));
                }
            }
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

    @PostMapping("/redis/reserve")
    public ResponseEntity<InventoryRedisReserveResponse> reserveRedis(
            @RequestBody(required = false) InventoryRedisReserveRequest request) {
        if (request == null || redisInventoryService == null) {
            return ResponseEntity.badRequest().body(InventoryRedisReserveResponse.rejected("request is required"));
        }
        try {
            RedisStockResult result =
                    redisInventoryService.reserve(request.orderId(), request.items());
            return ResponseEntity.ok(new InventoryRedisReserveResponse(result.status().name(), result.error()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(InventoryRedisReserveResponse.rejected(exception.getMessage()));
        } catch (RuntimeException exception) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(InventoryRedisReserveResponse.unknown("Redis inventory operation failed"));
        }
    }

    @PostMapping("/redis/compensate")
    public ResponseEntity<InventoryRedisCompensateResponse> compensateRedis(
            @RequestBody(required = false) InventoryRedisCompensateRequest request) {
        if (request == null || redisInventoryService == null) {
            return ResponseEntity.badRequest().body(InventoryRedisCompensateResponse.rejected("request is required"));
        }
        try {
            RedisStockResult result =
                    redisInventoryService.compensate(request.orderId(), request.items());
            return ResponseEntity.ok(new InventoryRedisCompensateResponse(result.status().name(), result.error()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(InventoryRedisCompensateResponse.rejected(exception.getMessage()));
        } catch (RuntimeException exception) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(InventoryRedisCompensateResponse.unknown("Redis inventory operation failed"));
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
