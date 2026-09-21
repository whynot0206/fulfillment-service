package com.why.fulfillment.inventory;

import com.why.fulfillment.api.inventory.InventoryReserveItem;
import com.why.fulfillment.inventory.mapper.SkuStockMapper;
import com.why.fulfillment.inventory.redis.RedisStockResultStatus;
import com.why.fulfillment.inventory.redis.RedisStockService;
import com.why.fulfillment.inventory.redis.RedisStockServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisStockServiceImplTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final SkuStockMapper stocks = mock(SkuStockMapper.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final RedisStockServiceImpl service = new RedisStockServiceImpl(redis, stocks);

    @Test
    void cancellationTombstoneRejectsLateReservationWithoutChangingStock() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(RedisStockService.reservationMarkerKey("10"))).thenReturn(null);
        when(values.get(RedisStockService.compensationMarkerKey("10"))).thenReturn("100:200:2");

        var result = service.reserve(10L, List.of(new InventoryReserveItem(100L, 200L, 2)));

        assertEquals(RedisStockResultStatus.CANCELED, result.status());
        verify(redis, never()).execute(any(), anyList(), any());
        verify(stocks, never()).selectBySkuId(any());
    }

    @Test
    void forcedCompensationCanCreateTombstoneBeforeReservationArrives() {
        when(redis.execute(any(), anyList(), any(), any()))
                .thenReturn(List.of(1L, 0L, 0L));

        var result = service.compensate(10L, List.of(new InventoryReserveItem(100L, 200L, 2)));

        assertEquals(RedisStockResultStatus.COMPENSATED, result.status());
    }

    @Test
    void ordinaryReleaseDoesNotCreateTombstoneWithoutRedisReservation() {
        when(redis.execute(any(), anyList(), any(), any()))
                .thenReturn(List.of(5L, 0L, 0L));

        var result = service.compensateIfReserved(
                10L, List.of(new InventoryReserveItem(100L, 200L, 2)));

        assertEquals(RedisStockResultStatus.NO_RESERVATION, result.status());
    }
}
