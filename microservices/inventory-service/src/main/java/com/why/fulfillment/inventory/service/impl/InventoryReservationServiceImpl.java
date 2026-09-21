package com.why.fulfillment.inventory.service.impl;

import com.why.fulfillment.api.inventory.InventoryQueryResponse;
import com.why.fulfillment.api.inventory.InventoryReserveItem;
import com.why.fulfillment.inventory.entity.SkuStock;
import com.why.fulfillment.inventory.entity.SkuStockLock;
import com.why.fulfillment.inventory.mapper.SkuStockLockMapper;
import com.why.fulfillment.inventory.mapper.SkuStockMapper;
import com.why.fulfillment.inventory.mapper.InventoryReservationFenceMapper;
import com.why.fulfillment.inventory.service.InventoryIdempotencyConflictException;
import com.why.fulfillment.inventory.service.InventoryReservationRejectedException;
import com.why.fulfillment.inventory.service.InventoryReservationService;
import com.why.fulfillment.inventory.service.InventorySkuNotFoundException;
import com.why.fulfillment.inventory.service.InventoryStockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class InventoryReservationServiceImpl implements InventoryReservationService {

    private final SkuStockMapper skuStockMapper;
    private final SkuStockLockMapper skuStockLockMapper;
    private final InventoryReservationFenceMapper fenceMapper;

    public InventoryReservationServiceImpl(SkuStockMapper skuStockMapper,
                                           SkuStockLockMapper skuStockLockMapper,
                                           InventoryReservationFenceMapper fenceMapper) {
        this.skuStockMapper = skuStockMapper;
        this.skuStockLockMapper = skuStockLockMapper;
        this.fenceMapper = fenceMapper;
    }

    /**
     * Reserves all rows in one local transaction.  Lock rows are inserted in
     * SKU order before their stock rows are updated, so reserve, release, and
     * confirm all acquire their database locks in the same order.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reserve(Long orderId, List<InventoryReserveItem> items) {
        requirePositive(orderId, "orderId");
        List<ReservationLine> requested = normalize(items);

        fenceMapper.ensureExists(orderId);
        Integer fenceStatus = fenceMapper.selectStatusForUpdate(orderId);
        if (fenceStatus == null || fenceStatus == InventoryReservationFenceMapper.CANCELED) {
            throw new InventoryReservationRejectedException(
                    "reservation for order " + orderId + " was canceled before reserve completed");
        }
        if (fenceStatus == InventoryReservationFenceMapper.CONFIRMED) {
            throw new InventoryReservationRejectedException(
                    "reservation for order " + orderId + " was already confirmed");
        }

        List<SkuStockLock> existing = skuStockLockMapper.selectByOrderIdForUpdate(orderId);
        if (!existing.isEmpty()) {
            ensureSamePayload(existing, requested, orderId);
            int state = commonState(existing);
            if (state == SkuStockLock.LOCKED) {
                return;
            }
            if (state == SkuStockLock.RELEASED) {
                throw new InventoryReservationRejectedException(
                        "reservation for order " + orderId + " was already released");
            }
            if (state == SkuStockLock.CONFIRMED) {
                throw new InventoryReservationRejectedException(
                        "reservation for order " + orderId + " was already confirmed");
            }
            throw new InventoryReservationRejectedException(
                    "reservation for order " + orderId + " has an invalid state");
        }

        // Insert the lock rows first.  If any stock update rejects, the
        // transaction rolls these rows back together with earlier updates.
        for (ReservationLine line : requested) {
            SkuStockLock lock = new SkuStockLock();
            lock.setOrderId(orderId);
            lock.setSkuId(line.skuId());
            lock.setSpuId(line.spuId());
            lock.setCount(line.count());
            lock.setStatus(SkuStockLock.LOCKED);
            try {
                if (skuStockLockMapper.insert(lock) != 1) {
                    throw new InventoryStockException("failed to create lock for sku " + line.skuId());
                }
            } catch (DuplicateKeyException exception) {
                // The unique orderId + skuId key is the database side of the
                // idempotency contract.  The transaction is rolled back and a
                // retry can safely inspect the committed lock on the next call.
                throw new InventoryReservationRejectedException(
                        "reservation for order " + orderId + " is already in progress");
            }
            if (skuStockMapper.reserve(line.skuId(), line.count()) != 1) {
                throw new InventoryReservationRejectedException(
                        "insufficient stock for sku " + line.skuId());
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void release(Long orderId) {
        requirePositive(orderId, "orderId");
        fenceMapper.ensureExists(orderId);
        Integer fenceStatus = fenceMapper.selectStatusForUpdate(orderId);
        if (fenceStatus != null && fenceStatus == InventoryReservationFenceMapper.CONFIRMED) {
            throw new InventoryReservationRejectedException(
                    "reservation for order " + orderId + " was already confirmed");
        }
        List<SkuStockLock> locks = skuStockLockMapper.selectByOrderIdForUpdate(orderId);
        for (SkuStockLock lock : locks) {
            if (lock.getStatus() == SkuStockLock.RELEASED) {
                continue;
            }
            if (lock.getStatus() == SkuStockLock.CONFIRMED) {
                throw new InventoryReservationRejectedException(
                        "reservation for order " + orderId + " was already confirmed");
            }
            if (lock.getStatus() != SkuStockLock.LOCKED) {
                throw new InventoryReservationRejectedException(
                        "reservation for order " + orderId + " has an invalid state");
            }
            if (skuStockLockMapper.markReleasedIfLocked(lock.getId()) == 1
                    && skuStockMapper.release(lock.getSkuId(), lock.getCount()) != 1) {
                throw new InventoryStockException("failed to release stock for sku " + lock.getSkuId());
            }
        }
        fenceMapper.updateStatus(orderId, InventoryReservationFenceMapper.CANCELED);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirm(Long orderId) {
        requirePositive(orderId, "orderId");
        fenceMapper.ensureExists(orderId);
        Integer fenceStatus = fenceMapper.selectStatusForUpdate(orderId);
        if (fenceStatus == null || fenceStatus == InventoryReservationFenceMapper.CANCELED) {
            throw new InventoryReservationRejectedException(
                    "reservation for order " + orderId + " was already canceled");
        }
        List<SkuStockLock> locks = skuStockLockMapper.selectByOrderIdForUpdate(orderId);
        if (locks.isEmpty()) {
            throw new InventoryReservationRejectedException(
                    "no reservation exists for order " + orderId);
        }
        for (SkuStockLock lock : locks) {
            if (lock.getStatus() == SkuStockLock.CONFIRMED) {
                continue;
            }
            if (lock.getStatus() == SkuStockLock.RELEASED) {
                throw new InventoryReservationRejectedException(
                        "reservation for order " + orderId + " was already released");
            }
            if (lock.getStatus() != SkuStockLock.LOCKED) {
                throw new InventoryReservationRejectedException(
                        "reservation for order " + orderId + " has an invalid state");
            }
            if (skuStockLockMapper.markConfirmedIfLocked(lock.getId()) == 1
                    && skuStockMapper.confirm(lock.getSkuId(), lock.getCount()) != 1) {
                throw new InventoryStockException("failed to confirm stock for sku " + lock.getSkuId());
            }
        }
        fenceMapper.updateStatus(orderId, InventoryReservationFenceMapper.CONFIRMED);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InventoryReserveItem> reservationItems(Long orderId) {
        requirePositive(orderId, "orderId");
        return skuStockLockMapper.selectByOrderId(orderId).stream()
                .map(lock -> new InventoryReserveItem(lock.getSkuId(), lock.getSpuId(), lock.getCount()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public InventoryQueryResponse query(Long skuId) {
        requirePositive(skuId, "skuId");
        SkuStock stock = skuStockMapper.selectBySkuId(skuId);
        if (stock == null) {
            throw new InventorySkuNotFoundException(skuId);
        }
        return new InventoryQueryResponse(stock.getSkuId(), stock.getSpuId(),
                stock.getStock(), stock.getLockStock());
    }

    private List<ReservationLine> normalize(List<InventoryReserveItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        Map<Long, ReservationLine> merged = new LinkedHashMap<>();
        for (InventoryReserveItem item : items) {
            if (item == null || item.skuId() == null || item.spuId() == null
                    || item.skuId() <= 0 || item.spuId() <= 0
                    || item.count() == null || item.count() <= 0) {
                throw new IllegalArgumentException("each item must contain positive skuId, spuId and count");
            }
            ReservationLine current = merged.get(item.skuId());
            if (current == null) {
                merged.put(item.skuId(), new ReservationLine(item.skuId(), item.spuId(), item.count()));
                continue;
            }
            if (!current.spuId().equals(item.spuId())) {
                throw new IllegalArgumentException("same skuId must use one spuId");
            }
            long total = (long) current.count() + item.count();
            if (total > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("reservation count is too large");
            }
            merged.put(item.skuId(), new ReservationLine(item.skuId(), item.spuId(), (int) total));
        }
        List<ReservationLine> ordered = new ArrayList<>(merged.values());
        ordered.sort(Comparator.comparing(ReservationLine::skuId));
        return ordered;
    }

    private void ensureSamePayload(List<SkuStockLock> existing,
                                   List<ReservationLine> requested,
                                   Long orderId) {
        if (existing.size() != requested.size()) {
            throw new InventoryIdempotencyConflictException(
                    "order " + orderId + " was already reserved with different items");
        }
        for (int index = 0; index < requested.size(); index++) {
            SkuStockLock lock = existing.get(index);
            ReservationLine line = requested.get(index);
            if (!line.skuId().equals(lock.getSkuId())
                    || !line.spuId().equals(lock.getSpuId())
                    || !line.count().equals(lock.getCount())) {
                throw new InventoryIdempotencyConflictException(
                        "order " + orderId + " was already reserved with different items");
            }
        }
    }

    private int commonState(List<SkuStockLock> locks) {
        int state = locks.get(0).getStatus();
        for (SkuStockLock lock : locks) {
            if (lock.getStatus() == null || lock.getStatus() != state) {
                return 0;
            }
        }
        return state;
    }

    private void requirePositive(Long value, String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private record ReservationLine(Long skuId, Long spuId, Integer count) {
    }
}
