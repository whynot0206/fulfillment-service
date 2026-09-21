package com.why.fulfillment.api.inventory;

import com.why.fulfillment.api.InternalFeignAuthConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Internal inventory HTTP contract used by order and payment services.
 *
 * <p>The URL is intentionally configurable so local deployments can use a
 * fixed port while a service registry can supply the service name later.</p>
 */
@FeignClient(name = "inventory-service", url = "${inventory.service.url:http://localhost:18082}",
        configuration = InternalFeignAuthConfiguration.class)
public interface InventoryClient {

    @PostMapping("/internal/inventory/reserve")
    InventoryReserveResponse reserve(@RequestBody InventoryReserveRequest request);

    @PostMapping("/internal/inventory/release")
    InventoryReleaseResponse release(@RequestBody InventoryReleaseRequest request);

    @PostMapping("/internal/inventory/confirm")
    InventoryConfirmResponse confirm(@RequestBody InventoryConfirmRequest request);

    @PostMapping("/internal/inventory/redis/reserve")
    InventoryRedisReserveResponse reserveRedis(@RequestBody InventoryRedisReserveRequest request);

    @PostMapping("/internal/inventory/redis/compensate")
    InventoryRedisCompensateResponse compensateRedis(@RequestBody InventoryRedisCompensateRequest request);

    @PostMapping("/internal/inventory/redis/materialize")
    InventoryRedisMaterializeResponse materializeRedis(@RequestBody InventoryRedisMaterializeRequest request);

    @GetMapping("/internal/inventory/query/{skuId}")
    InventoryQueryResponse query(@PathVariable("skuId") Long skuId);
}
