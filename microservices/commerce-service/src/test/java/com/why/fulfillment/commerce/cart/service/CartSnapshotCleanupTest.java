package com.why.fulfillment.commerce.cart.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.why.fulfillment.commerce.cart.entity.CartItem;
import com.why.fulfillment.commerce.cart.mapper.CartItemMapper;
import com.why.fulfillment.commerce.product.mapper.ProductImageMapper;
import com.why.fulfillment.commerce.product.mapper.ProductSkuMapper;
import com.why.fulfillment.commerce.product.mapper.ProductSpuMapper;
import com.why.fulfillment.commerce.product.service.SkuAvailabilityService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CartSnapshotCleanupTest {
    private final CartItemMapper mapper = mock(CartItemMapper.class);
    private final CartCache cache = mock(CartCache.class);
    private final CartService service = new CartService(mapper, cache, mock(ProductSkuMapper.class),
            mock(ProductSpuMapper.class), mock(ProductImageMapper.class), mock(SkuAvailabilityService.class), 50, 200);

    @Test
    void selectedItemsCarryDatabaseRowIdentityAndRevisionWithoutUsingCache() {
        CartItem selected = item(101L, 1001L, 7L, true);
        CartItem unselected = item(102L, 1002L, 8L, false);
        when(mapper.selectByUserId(9L)).thenReturn(List.of(selected, unselected));

        assertEquals(List.of(new CartItemSnapshot(1001L, 2, true, 101L, 7L)), service.selectedItems(9L));
        verifyNoInteractions(cache);
    }

    @Test
    void allUnchangedOriginalRowsCanBeCleared() {
        var snapshots = List.of(new CartItemSnapshot(1001L, 2, true, 101L, 7L));
        when(mapper.deleteUnchangedSnapshots(9L, snapshots)).thenReturn(1);

        assertTrue(service.removeCheckedOutSnapshot(9L, snapshots));

        verify(mapper).deleteUnchangedSnapshots(9L, snapshots);
        verify(cache).evictAfterTransaction(9L);
    }

    @Test
    void partialCasMatchKeepsCleanupNoticeForEditedOrReaddedRows() {
        var snapshots = List.of(new CartItemSnapshot(1001L, 2, true, 101L, 7L),
                new CartItemSnapshot(1002L, 1, true, 102L, 3L));
        when(mapper.deleteUnchangedSnapshots(9L, snapshots)).thenReturn(1);

        assertFalse(service.removeCheckedOutSnapshot(9L, snapshots));

        verify(cache).evictAfterTransaction(9L);
    }

    @Test
    void lostRevisionOrRowIdentityCannotTriggerFallbackDeleteBySku() {
        assertFalse(service.removeCheckedOutSnapshot(9L, List.of(new CartItemSnapshot(1001L, 2, true))));
        assertFalse(service.removeCheckedOutSnapshot(9L, List.of(new CartItemSnapshot(1001L, 2, true, 101L, null))));
        assertFalse(service.removeCheckedOutSnapshot(9L, List.of(new CartItemSnapshot(1001L, 2, false, 101L, 0L))));
        verifyNoInteractions(mapper, cache);
    }

    @Test
    void duplicateSnapshotIdentitiesAreRejectedBeforeDelete() {
        var snapshot = new CartItemSnapshot(1001L, 2, true, 101L, 7L);
        assertFalse(service.removeCheckedOutSnapshot(9L, List.of(snapshot, snapshot)));
        verifyNoInteractions(mapper, cache);
    }

    @Test
    void cleanupExceptionIsNotConvertedToSuccess() {
        var snapshots = List.of(new CartItemSnapshot(1001L, 2, true, 101L, 7L));
        when(mapper.deleteUnchangedSnapshots(9L, snapshots)).thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(IllegalStateException.class, () -> service.removeCheckedOutSnapshot(9L, snapshots));
        verifyNoInteractions(cache);
    }

    @Test
    void oldCacheShapeStillDeserializesButCannotAuthorizeCleanup() throws Exception {
        CartItemSnapshot snapshot = new ObjectMapper().readValue(
                "{\"skuId\":1001,\"quantity\":2,\"selected\":true}", CartItemSnapshot.class);
        assertEquals(1001L, snapshot.skuId());
        assertNull(snapshot.rowId());
        assertNull(snapshot.revision());
        assertFalse(service.removeCheckedOutSnapshot(9L, List.of(snapshot)));
        verifyNoInteractions(mapper, cache);
    }

    private static CartItem item(long id, long skuId, long revision, boolean selected) {
        CartItem row = new CartItem();
        row.setId(id);
        row.setUserId(9L);
        row.setSkuId(skuId);
        row.setQuantity(2);
        row.setSelected(selected);
        row.setRevision(revision);
        return row;
    }
}
