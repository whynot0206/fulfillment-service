package com.why.fulfillment.commerce.checkout.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.why.fulfillment.api.order.OrderClient;
import com.why.fulfillment.api.order.OrderCreateItem;
import com.why.fulfillment.api.order.OrderCreateRequest;
import com.why.fulfillment.api.order.OrderCreateResponse;
import com.why.fulfillment.commerce.checkout.dto.CheckoutResultView;
import com.why.fulfillment.commerce.checkout.entity.CheckoutRequest;
import com.why.fulfillment.commerce.checkout.mapper.CheckoutRequestMapper;
import com.why.fulfillment.commerce.common.CommerceException;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static com.why.fulfillment.commerce.checkout.entity.CheckoutRequest.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CheckoutRecoveryServiceTest {
    private static final long ID = 77;
    private static final long ORDER_ID = 9007199254740993L;
    private final CheckoutRequestMapper mapper = mock(CheckoutRequestMapper.class);
    private final OrderClient order = mock(OrderClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final CheckoutRecoveryService service = newService();
    private CheckoutRequest stored;

    private CheckoutRecoveryService newService() {
        return new CheckoutRecoveryService(mapper, order, json, 30, 3, 2, 10, 1800, 30);
    }

    @BeforeEach
    void fixture() {
        stored = service.prepare(9L, "test-key", "digest", original());
        stored.setId(ID);
        when(mapper.selectOwned(eq(ID), anyString())).thenAnswer(call -> stored);
        when(mapper.selectById(ID)).thenAnswer(call -> stored);
        when(mapper.creationAllowed(eq(ID), anyString())).thenReturn(1);
        when(mapper.tryClaim(eq(ID), anyString(), eq(30L), anyInt())).thenAnswer(call -> {
            stored.setLeaseOwner(call.getArgument(1));
            stored.setAttemptCount(stored.getAttemptCount() + 1);
            return 1;
        });
        when(mapper.finishSubmitted(eq(ID), anyString(), anyString(), anyBoolean())).thenAnswer(call -> {
            stored.setStatus(STATUS_SUBMITTED);
            stored.setResultState(call.getArgument(2));
            stored.setCartCleanupRequired(call.getArgument(3));
            return 1;
        });
        when(mapper.finishRejected(eq(ID), anyString(), anyString(), anyString())).thenAnswer(call -> {
            stored.setStatus(STATUS_REJECTED);
            stored.setResultState(call.getArgument(2));
            stored.setLastError(call.getArgument(3));
            return 1;
        });
        when(mapper.deferOwned(eq(ID), anyString(), anyString(), anyInt(), anyLong())).thenAnswer(call -> {
            stored.setRecoveryState(call.getArgument(3));
            return 1;
        });
    }

    @Test
    void firstInsertContainsIdFullImmutablePayloadAndBoundedWindow() throws Exception {
        assertThat(stored.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(json.readValue(stored.getRequestPayload(), OrderCreateRequest.class)).isEqualTo(original());
        assertThat(stored.getRecoveryWindowSeconds()).isEqualTo(120);
        assertThat(stored.getLeaseOwner()).isNotBlank();
        assertThat(stored.getAttemptCount()).isEqualTo(1);
        String sql = String.join(" ", CheckoutRequestMapper.class.getMethod("insertClaim", CheckoutRequest.class)
                .getAnnotation(Insert.class).value());
        assertThat(sql).contains("#{orderId}", "#{requestPayload}", "#{leaseOwner}", "#{recoveryWindowSeconds}");
    }

    @Test
    void lostCreateResponseCanBeResolvedByANewServiceWithoutCreatingAgain() {
        when(order.create(any())).thenThrow(new IllegalStateException("response lost"));
        assertCode(() -> service.executeInitial(stored), "CHECKOUT_RESULT_UNKNOWN");
        assertThat(stored.getStatus()).isEqualTo(STATUS_IN_PROGRESS);
        String immutable = stored.getRequestPayload();

        when(order.resolveCreate(any())).thenReturn(response("RESERVED"));
        newService().recover(ID);

        assertThat(stored.getStatus()).isEqualTo(STATUS_SUBMITTED);
        assertThat(stored.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(stored.getRequestPayload()).isEqualTo(immutable);
        assertThat(stored.getCartCleanupRequired()).isTrue();
        ArgumentCaptor<OrderCreateRequest> calls = ArgumentCaptor.forClass(OrderCreateRequest.class);
        verify(order).create(calls.capture());
        verify(order).resolveCreate(calls.capture());
        assertThat(calls.getAllValues()).containsExactly(original(), original());
        CheckoutResultView result = service.replay(stored);
        assertThat(result.orderId()).isEqualTo(ORDER_ID);
        assertThat(result.replayed()).isTrue();
        assertThat(result.cartCleanupRequired()).isTrue();
        verify(mapper, never()).markCartCleaned(anyLong());
    }

    @Test
    void recoveryCreatesOnlyTheStoredIntentWhileWindowRemainsOpen() {
        when(order.resolveCreate(any())).thenReturn(response("NOT_FOUND"));
        when(order.create(any())).thenReturn(response("RESERVED"));
        service.recover(ID);
        verify(order).create(original());
        assertThat(stored.getStatus()).isEqualTo(STATUS_SUBMITTED);
        assertThat(stored.getCartCleanupRequired()).isTrue();
    }

    @Test
    void anExpiredMissingIntentRequiresReviewInsteadOfADeferredNewPurchase() {
        when(mapper.creationAllowed(eq(ID), anyString())).thenReturn(0);
        when(order.resolveCreate(any())).thenReturn(response("NOT_FOUND"));
        service.recover(ID);
        verify(order, never()).create(any());
        assertThat(stored.getStatus()).isEqualTo(STATUS_IN_PROGRESS);
        assertThat(stored.getRecoveryState()).isEqualTo(RECOVERY_MANUAL);
        when(mapper.tryClaim(eq(ID), anyString(), eq(30L), eq(RECOVERY_MANUAL))).thenReturn(0);
        assertCode(() -> service.replay(stored), "CHECKOUT_RECOVERY_REQUIRED");
    }

    @Test
    void windowMustBeCheckedAgainAfterResolveBeforeCreating() {
        when(mapper.creationAllowed(eq(ID), anyString())).thenReturn(1, 0);
        when(order.resolveCreate(any())).thenReturn(response("NOT_FOUND"));
        service.recover(ID);
        verify(order, never()).create(any());
        assertThat(stored.getRecoveryState()).isEqualTo(RECOVERY_MANUAL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RESERVING", "PENDING_COMPENSATION", "UNKNOWN"})
    void unresolvedStatesAreRetriedNotRejected(String state) {
        when(order.resolveCreate(any())).thenReturn(response(state));
        service.recover(ID);
        assertThat(stored.getStatus()).isEqualTo(STATUS_IN_PROGRESS);
        assertThat(stored.getRecoveryState()).isEqualTo(RECOVERY_ACTIVE);
        verify(mapper, never()).finishRejected(anyLong(), anyString(), anyString(), anyString());
        verify(order, never()).create(any());
    }

    @Test
    void retriesAreBoundedAndExhaustionIsNotDeterministicFailure() {
        stored.setAttemptCount(2);
        when(order.resolveCreate(any())).thenThrow(new IllegalStateException("offline"));
        service.recover(ID);
        assertThat(stored.getStatus()).isEqualTo(STATUS_IN_PROGRESS);
        assertThat(stored.getRecoveryState()).isEqualTo(RECOVERY_MANUAL);
        assertThat(service.backoffSeconds(1)).isEqualTo(2);
        assertThat(service.backoffSeconds(2)).isEqualTo(4);
        assertThat(service.backoffSeconds(1000)).isEqualTo(10);
    }

    @Test
    void anExpiredIntentMayResolvePaidButDoesNotTellTheBrowserToPayAgain() {
        when(mapper.creationAllowed(eq(ID), anyString())).thenReturn(0);
        when(order.resolveCreate(any())).thenReturn(response("PAID"));
        service.recover(ID);
        CheckoutResultView result = service.replay(stored);
        assertThat(stored.getResultState()).isEqualTo("PAID");
        assertThat(result.state()).isEqualTo("RESERVED");
        assertThat(result.message()).contains("当前状态").doesNotContain("请尽快付款");
        assertThat(result.cartCleanupRequired()).isTrue();
        verify(order, never()).create(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELED", "CLOSED", "FAILED", "COMPENSATED"})
    void onlyKnownFinalFailuresAreRecordedAsRejected(String state) {
        when(order.resolveCreate(any())).thenReturn(response(state));
        service.recover(ID);
        assertThat(stored.getStatus()).isEqualTo(STATUS_REJECTED);
        assertThat(service.replay(stored).state()).isEqualTo(state);
        verify(order, never()).create(any());
    }

    @Test
    void aLeaseLoserCannotStartARemoteAttempt() {
        when(mapper.tryClaim(eq(ID), anyString(), eq(30L), eq(RECOVERY_ACTIVE))).thenReturn(0);
        service.recover(ID);
        verifyNoInteractions(order);
    }

    @Test
    void lateSuccessCannotOverwriteANewerFinalResultOrClearCart() {
        when(order.create(any())).thenAnswer(call -> {
            // A different owner completed cancellation while the initial HTTP response was delayed.
            stored.setStatus(STATUS_REJECTED);
            stored.setResultState("CANCELED");
            stored.setLastError("原订单已关闭");
            return response("RESERVED");
        });
        when(mapper.finishSubmitted(eq(ID), anyString(), anyString(), anyBoolean())).thenReturn(0);
        CheckoutRecoveryService.InitialResult result = service.executeInitial(stored);
        assertThat(result.view().state()).isEqualTo("CANCELED");
        assertThat(result.cleanupEligible()).isFalse();
        assertThat(stored.getStatus()).isEqualTo(STATUS_REJECTED);
    }

    @Test
    void lateInitialResultCannotCleanACartAfterBackgroundRecovery() {
        when(order.create(any())).thenAnswer(call -> {
            stored.setStatus(STATUS_SUBMITTED);
            stored.setResultState("RESERVED");
            stored.setCartCleanupRequired(true);
            return response("RESERVED");
        });
        when(mapper.finishSubmitted(eq(ID), anyString(), anyString(), anyBoolean())).thenReturn(0);
        CheckoutRecoveryService.InitialResult result = service.executeInitial(stored);
        assertThat(result.view().cartCleanupRequired()).isTrue();
        assertThat(result.cleanupEligible()).isFalse();
    }

    @Test
    void successIsConservativelyMarkedUncleanBeforeTheSynchronousCartCas() {
        when(order.create(any())).thenReturn(response("RESERVED"));
        CheckoutRecoveryService.InitialResult result = service.executeInitial(stored);
        assertThat(result.cleanupEligible()).isTrue();
        assertThat(result.view().cartCleanupRequired()).isTrue();
        assertThat(stored.getCartCleanupRequired()).isTrue();
    }

    @Test
    void legacyUnassociatedRowsAreNeverReconstructedFromTodaysCart() {
        stored.setOrderId(null);
        stored.setRequestPayload(null);
        stored.setRecoveryState(RECOVERY_MANUAL);
        assertCode(() -> service.replay(stored), "CHECKOUT_RECOVERY_REQUIRED");
        verifyNoInteractions(order);
        verify(mapper, never()).tryClaim(anyLong(), anyString(), anyLong(), anyInt());
    }

    @Test
    void corruptedSnapshotRequiresReviewWithoutRemoteWrites() {
        stored.setRequestPayload("{\"orderId\":1}");
        service.recover(ID);
        assertThat(stored.getRecoveryState()).isEqualTo(RECOVERY_MANUAL);
        verifyNoInteractions(order);
    }

    @Test
    void manualRecheckNeverCreatesEvenIfOriginalWindowIsStillOpen() {
        stored.setRecoveryState(RECOVERY_MANUAL);
        when(order.resolveCreate(any())).thenReturn(response("NOT_FOUND"));
        assertCode(() -> service.replay(stored), "CHECKOUT_RECOVERY_REQUIRED");
        verify(order, never()).create(any());
        verify(order).resolveCreate(original());
    }

    @Test
    void manualRecheckCanConvergeOnALaterPaidFact() {
        stored.setRecoveryState(RECOVERY_MANUAL);
        when(order.resolveCreate(any())).thenReturn(response("PAID"));
        CheckoutResultView result = service.replay(stored);
        assertThat(result.state()).isEqualTo("RESERVED");
        assertThat(result.cartCleanupRequired()).isTrue();
        assertThat(stored.getResultState()).isEqualTo("PAID");
        verify(order, never()).create(any());
    }

    @Test
    void conflictsAndWrongOrderIdsRequireReviewNotRejection() {
        when(order.resolveCreate(any())).thenReturn(new OrderCreateResponse(1L, "RESERVED", "wrong", true));
        service.recover(ID);
        assertThat(stored.getRecoveryState()).isEqualTo(RECOVERY_MANUAL);
        verify(mapper, never()).finishSubmitted(anyLong(), anyString(), anyString(), anyBoolean());
        verify(mapper, never()).finishRejected(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void everyOwnedStateTransitionGuardsOwnerPendingStateAndLiveLease() {
        for (var method : CheckoutRequestMapper.class.getMethods()) {
            if (!List.of("finishSubmitted", "finishRejected", "deferOwned").contains(method.getName())) {
                continue;
            }
            String sql = String.join(" ", method.getAnnotation(Update.class).value());
            assertThat(sql).contains("status=0", "lease_owner=#{owner}", "lease_until>current_timestamp");
        }
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOf(CommerceException.class)
                .satisfies(error -> assertThat(((CommerceException) error).getCode()).isEqualTo(code));
    }

    private static OrderCreateRequest original() {
        return new OrderCreateRequest(ORDER_ID, 9L, new BigDecimal("20.00"), 120L,
                List.of(new OrderCreateItem(1001L, 2001L, 2, new BigDecimal("10.00"), "旧商品名", "{\"size\":\"M\"}")));
    }

    private static OrderCreateResponse response(String state) {
        return new OrderCreateResponse(ORDER_ID, state, "safe fixture", true);
    }
}
