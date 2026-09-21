package com.why.fulfillment.inventory.web;

import com.why.fulfillment.api.inventory.InventoryQueryResponse;
import com.why.fulfillment.inventory.service.InventoryReservationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inventory")
public class PublicInventoryController {
    private final InventoryReservationService reservationService;

    public PublicInventoryController(InventoryReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @GetMapping("/skus/{skuId}")
    public ResponseEntity<InventoryQueryResponse> query(@PathVariable Long skuId) {
        try {
            return ResponseEntity.ok(reservationService.query(skuId));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().build();
        } catch (RuntimeException exception) {
            return ResponseEntity.notFound().build();
        }
    }
}
