package com.why.fulfillment.inventory.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.why.fulfillment.api.inventory.InventoryReserveItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class InventoryRedisLedgerService {
    public static final int PENDING = 1;
    public static final int MATERIALIZED = 2;
    public static final int COMPENSATED = 3;
    private static final TypeReference<List<InventoryReserveItem>> ITEM_LIST = new TypeReference<>() { };

    private final InventoryRedisReservationRepository repository;
    private final ObjectMapper objectMapper;

    public InventoryRedisLedgerService(InventoryRedisReservationRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void recordPending(Long orderId, List<InventoryReserveItem> items) {
        String json = serialize(normalize(orderId, items));
        repository.insertIfAbsent(orderId, json, PENDING);
        var entry = requireSamePayload(orderId, json);
        if (entry.status() == COMPENSATED) {
            throw new LedgerStateException("Redis reservation was already compensated");
        }
    }

    @Transactional
    public void recordMaterialized(Long orderId, List<InventoryReserveItem> items) {
        String json = serialize(normalize(orderId, items));
        repository.insertIfAbsent(orderId, json, MATERIALIZED);
        var entry = requireSamePayload(orderId, json);
        if (entry.status() == COMPENSATED) {
            throw new LedgerStateException("Redis reservation was already compensated");
        }
        if (entry.status() != MATERIALIZED
                && !repository.markMaterialized(orderId, entry.itemsJson())) {
            throw new IllegalStateException("failed to materialize Redis reservation ledger");
        }
    }

    @Transactional
    public void recordCompensated(Long orderId, List<InventoryReserveItem> items) {
        String json = serialize(normalize(orderId, items));
        repository.insertIfAbsent(orderId, json, COMPENSATED);
        var entry = requireSamePayload(orderId, json);
        if (entry.status() != COMPENSATED
                && !repository.markCompensated(orderId, entry.itemsJson())) {
            throw new IllegalStateException("failed to compensate Redis reservation ledger");
        }
    }

    @Transactional(readOnly = true)
    public List<PendingReservation> pendingReservations() {
        List<PendingReservation> pending = new ArrayList<>();
        for (var entry : repository.findPending()) {
            try {
                pending.add(new PendingReservation(entry.orderId(), objectMapper.readValue(entry.itemsJson(), ITEM_LIST)));
            } catch (JsonProcessingException exception) {
                pending.add(new PendingReservation(entry.orderId(), null));
            }
        }
        return pending;
    }

    private InventoryRedisReservationRepository.LedgerEntry requireSamePayload(long orderId, String json) {
        var entry = repository.find(orderId)
                .orElseThrow(() -> new IllegalStateException("Redis reservation ledger was not persisted"));
        if (!samePayload(entry.itemsJson(), json)) {
            throw new LedgerConflictException("orderId already has a different inventory payload");
        }
        return entry;
    }

    private boolean samePayload(String storedJson, String expectedJson) {
        try {
            List<InventoryReserveItem> stored = normalize(1L, objectMapper.readValue(storedJson, ITEM_LIST));
            List<InventoryReserveItem> expected = normalize(1L, objectMapper.readValue(expectedJson, ITEM_LIST));
            return stored.equals(expected);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return false;
        }
    }

    private List<InventoryReserveItem> normalize(Long orderId, List<InventoryReserveItem> items) {
        if (orderId == null || orderId <= 0 || items == null || items.isEmpty()) {
            throw new IllegalArgumentException("positive orderId and inventory items are required");
        }
        Map<Long, InventoryReserveItem> merged = new TreeMap<>();
        for (InventoryReserveItem item : items) {
            if (item == null || item.skuId() == null || item.skuId() <= 0
                    || item.spuId() == null || item.spuId() <= 0
                    || item.count() == null || item.count() <= 0) {
                throw new IllegalArgumentException("inventory item fields must be positive");
            }
            InventoryReserveItem current = merged.get(item.skuId());
            if (current == null) {
                merged.put(item.skuId(), item);
            } else if (!current.spuId().equals(item.spuId())) {
                throw new IllegalArgumentException("same skuId must use one spuId");
            } else {
                merged.put(item.skuId(), new InventoryReserveItem(item.skuId(), item.spuId(),
                        Math.addExact(current.count(), item.count())));
            }
        }
        return List.copyOf(merged.values());
    }

    private String serialize(List<InventoryReserveItem> items) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("inventory items cannot be serialized", exception);
        }
    }

    public record PendingReservation(long orderId, List<InventoryReserveItem> items) { }

    public static class LedgerConflictException extends IllegalStateException {
        public LedgerConflictException(String message) { super(message); }
    }

    public static class LedgerStateException extends IllegalStateException {
        public LedgerStateException(String message) { super(message); }
    }
}
