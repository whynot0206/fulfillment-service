package com.why.fulfillment.order.repository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SQL/affected-row contracts; actual MySQL lease races require isolated integration acceptance. */
class ConfirmationOutboxRepositoryTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ConfirmationOutboxRepository repository = new ConfirmationOutboxRepository(jdbc);

    @Test
    void eachSuccessfulClaimUsesANewAttemptTokenAndDatabaseClock() {
        when(jdbc.update(anyString(), anyString(), eq(60), eq(7L))).thenReturn(1);
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(7L), anyString())).thenReturn("10");

        var first = repository.claimConfirmationEvent(7L).orElseThrow();
        var second = repository.claimConfirmationEvent(7L).orElseThrow();

        assertEquals(10L, first.orderId());
        assertEquals(7L, first.eventId());
        assertNotEquals(first.leaseOwner(), second.leaseOwner());
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).update(sql.capture(), anyString(), eq(60), eq(7L));
        assertTrue(sql.getValue().contains("lease_owner = ?"));
        assertTrue(sql.getValue().contains("DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL ? SECOND)"));
        assertTrue(sql.getValue().contains("AND status = 0 AND next_retry_time <= CURRENT_TIMESTAMP"));
        assertTrue(sql.getValue().contains("event_type = 'PAYMENT_CONFIRMED'"));
    }

    @Test
    void lostClaimDoesNotReadOrReturnBusinessPayload() {
        when(jdbc.update(anyString(), anyString(), eq(60), eq(7L))).thenReturn(0);

        assertTrue(repository.claimConfirmationEvent(7L).isEmpty());

        verify(jdbc, never()).queryForObject(anyString(), eq(String.class), eq(7L), anyString());
    }

    @Test
    void completionRequiresOriginalOwnerUnexpiredLeaseAndProcessingState() {
        when(jdbc.update(anyString(), eq(7L), eq("old-owner"))).thenReturn(0);

        assertFalse(repository.markConfirmationSent(7L, "old-owner"));

        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(7L), eq("old-owner"));
        assertLeaseFence(sql.getValue());
        assertTrue(sql.getValue().contains("lease_owner = NULL, lease_until = NULL"));
        assertTrue(sql.getValue().contains("SET status = 2"));
    }

    @Test
    void retryCannotResetNewOwnerOrSentEventAndKeepsBoundedBackoff() {
        when(jdbc.update(anyString(), eq("offline"), eq(7L), eq("old-owner"))).thenReturn(0);

        assertFalse(repository.retryConfirmation(7L, "old-owner", "offline"));

        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq("offline"), eq(7L), eq("old-owner"));
        assertLeaseFence(sql.getValue());
        assertTrue(sql.getValue().contains("retry_count >= 9 THEN 3 ELSE 0"));
        assertTrue(sql.getValue().contains("LEAST(retry_count + 1, 8)"));
        assertTrue(sql.getValue().contains("retry_count = retry_count + 1"));
        assertTrue(sql.getValue().contains("lease_owner = NULL, lease_until = NULL"));
    }

    @Test
    void validOwnerCanCompleteAndErrorsAreBounded() {
        when(jdbc.update(anyString(), eq(7L), eq("current-owner"))).thenReturn(1);
        when(jdbc.update(anyString(), eq("x".repeat(500)), eq(8L), eq("current-owner"))).thenReturn(1);

        assertTrue(repository.markConfirmationSent(7L, "current-owner"));
        assertTrue(repository.retryConfirmation(8L, "current-owner", "x".repeat(600)));
    }

    @Test
    void recoveryAcceptsOnlyExpiredOrStaleLegacyClaimsNotReadyOrSentRows() {
        when(jdbc.update(anyString())).thenReturn(2);

        assertEquals(2, repository.recoverStaleConfirmationClaims());

        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture());
        assertTrue(sql.getValue().contains("event_type = 'PAYMENT_CONFIRMED' AND status = 1"));
        assertTrue(sql.getValue().contains("lease_until <= CURRENT_TIMESTAMP(6)"));
        assertTrue(sql.getValue().contains("lease_until IS NULL"));
        assertTrue(sql.getValue().contains("update_time < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 MINUTE)"));
        assertTrue(sql.getValue().contains("lease_owner = NULL, lease_until = NULL"));
    }

    private static void assertLeaseFence(String sql) {
        String predicate = sql.substring(sql.indexOf("WHERE"));
        assertTrue(predicate.contains("event_id = ?"));
        assertTrue(predicate.contains("event_type = 'PAYMENT_CONFIRMED' AND status = 1"));
        assertTrue(predicate.contains("lease_owner = ? AND lease_until > CURRENT_TIMESTAMP(6)"));
    }
}
