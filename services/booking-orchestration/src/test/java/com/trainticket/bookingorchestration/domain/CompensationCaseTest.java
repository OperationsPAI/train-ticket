package com.trainticket.bookingorchestration.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class CompensationCaseTest {
    private static final Instant NOW = Instant.parse("2026-07-03T12:00:00Z");

    @Test
    void opensCompensationCaseWithCorrectFields() {
        CompensationCase cc = CompensationCase.open("saga-1", "provider reservation failed", NOW);

        assertEquals("saga-1", cc.sagaId());
        assertEquals("provider reservation failed", cc.compensationReason());
        assertFalse(cc.isClosed());
        assertFalse(cc.isEscalated());
    }

    @Test
    void requestsProviderCancellationAndHoldRelease() {
        CompensationCase cc = CompensationCase.open("saga-1", "provider reservation failed", NOW);

        cc.requestProviderCancellation("PROV-REF-1");
        assertEquals(1, cc.providerCancellationRequests());

        cc.requestHoldRelease("hold-1");
        assertEquals(1, cc.holdReleaseRequests());

        cc.requestRefund("payment-intent-1");
        assertEquals(1, cc.refundRequests());
    }

    @Test
    void closesCompensationCase() {
        CompensationCase cc = CompensationCase.open("saga-1", "provider reservation failed", NOW);

        cc.close();
        assertTrue(cc.isClosed());
    }

    @Test
    void closesIdempotently() {
        CompensationCase cc = CompensationCase.open("saga-1", "reason", NOW);

        cc.close();
        cc.close();
        assertTrue(cc.isClosed());
    }

    @Test
    void escalatesCompensationCase() {
        CompensationCase cc = CompensationCase.open("saga-1", "reason", NOW);

        cc.escalate("provider unresponsive");
        assertTrue(cc.isEscalated());
        assertFalse(cc.isClosed());
    }

    @Test
    void refusesActionsAfterClose() {
        CompensationCase cc = CompensationCase.open("saga-1", "reason", NOW);
        cc.close();

        assertThrows(IllegalStateException.class, () ->
            cc.requestProviderCancellation("PROV-REF-1"));
        assertThrows(IllegalStateException.class, () ->
            cc.requestHoldRelease("hold-1"));
        assertThrows(IllegalStateException.class, () ->
            cc.requestRefund("payment-intent-1"));
        assertThrows(IllegalStateException.class, () ->
            cc.escalate("reason"));
    }

    @Test
    void rejectsEmptySagaId() {
        assertThrows(IllegalArgumentException.class, () ->
            CompensationCase.open("", "reason", NOW));
    }

    @Test
    void rejectsEmptyCompensationReason() {
        assertThrows(IllegalArgumentException.class, () ->
            CompensationCase.open("saga-1", "", NOW));
    }
}
