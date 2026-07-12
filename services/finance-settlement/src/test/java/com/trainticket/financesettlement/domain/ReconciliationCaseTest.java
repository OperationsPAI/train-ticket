package com.trainticket.financesettlement.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReconciliationCaseTest {
    private static final Instant NOW = Instant.parse("2026-07-03T12:00:00Z");

    @Test
    void opensCaseWithDifferentAmounts() {
        ReconciliationCase rc = ReconciliationCase.open("order-1", "payment-1",
            "amount-mismatch", Money.of("CNY", "120.00"), Money.of("CNY", "100.00"),
            "Expected 120.00 but channel reports 100.00", NOW, "cmd-open", "corr-1");
        assertNotNull(rc.reconciliationCaseId());
        assertEquals("amount-mismatch", rc.differenceType());
        assertEquals(ReconciliationCase.ReconciliationCaseStatus.OPEN, rc.status());
        assertEquals(1, rc.domainEvents().size());
        assertInstanceOf(ReconciliationCaseOpened.class, rc.domainEvents().getFirst());
    }

    @Test
    void refusesOpenCaseWithMatchingAmounts() {
        assertThrows(DomainRuleViolation.class, () -> ReconciliationCase.open(
            "order-1", null, "duplicate", Money.of("CNY", "100.00"), Money.of("CNY", "100.00"),
            "Same amounts", NOW, "cmd-open", "corr-1"));
    }

    @Test
    void opensCaseWithDifferentCurrencies() {
        ReconciliationCase rc = ReconciliationCase.open("order-1", null, "currency-mismatch",
            Money.of("CNY", "100.00"), Money.of("USD", "15.00"),
            "Platform in CNY, channel in USD", NOW, "cmd-open", "corr-1");
        assertEquals("currency-mismatch", rc.differenceType());
        assertEquals(ReconciliationCase.ReconciliationCaseStatus.OPEN, rc.status());
    }

    @Test
    void resolveTransitionsToResolvedAndEmitsEvent() {
        ReconciliationCase rc = ReconciliationCase.open("order-1", null, "missing-in-platform",
            Money.of("CNY", "50.00"), Money.of("CNY", "0.00"),
            "Channel record not found in platform", NOW, "cmd-open", "corr-1");
        rc.resolve("auto-resolved", "Test transaction", NOW.plusSeconds(10), "cmd-resolve", "event-1", "corr-1");
        assertEquals(ReconciliationCase.ReconciliationCaseStatus.RESOLVED, rc.status());
        assertEquals("auto-resolved", rc.resolution());
        assertEquals(2, rc.domainEvents().size());
        assertInstanceOf(ReconciliationCaseResolved.class, rc.domainEvents().get(1));
    }

    @Test
    void resolveIsIdempotent() {
        ReconciliationCase rc = ReconciliationCase.open("order-1", null, "amount-mismatch",
            Money.of("CNY", "120.00"), Money.of("CNY", "100.00"),
            "Amount mismatch", NOW, "cmd-open", "corr-1");
        rc.resolve("manual-resolved", "Fixed", NOW.plusSeconds(10), "cmd-resolve", "event-1", "corr-1");
        rc.resolve("again", "Already done", NOW.plusSeconds(20), "cmd-resolve2", "event-2", "corr-1");
        assertEquals(2, rc.domainEvents().size());
        assertEquals("manual-resolved", rc.resolution());
    }

    @Test
    void cannotResolveRejectedCase() {
        ReconciliationCase rc = ReconciliationCase.open("order-1", null, "duplicate",
            Money.of("CNY", "100.00"), Money.of("CNY", "0.00"),
            "Duplicate", NOW, "cmd-open", "corr-1");
        rc.reject("Invalid", NOW.plusSeconds(10), "cmd-reject", "event-1", "corr-1");
        assertThrows(DomainRuleViolation.class, () ->
            rc.resolve("manual-resolved", "Should not work", NOW.plusSeconds(20), "cmd-resolve", "event-2", "corr-1"));
    }

    @Test
    void rejectTransitionsToRejected() {
        ReconciliationCase rc = ReconciliationCase.open("order-1", null, "duplicate",
            Money.of("CNY", "100.00"), Money.of("CNY", "0.00"),
            "Duplicate", NOW, "cmd-open", "corr-1");
        rc.reject("Not valid", NOW.plusSeconds(10), "cmd-reject", "event-1", "corr-1");
        assertEquals(ReconciliationCase.ReconciliationCaseStatus.REJECTED, rc.status());
        assertEquals("rejected", rc.resolution());
        assertEquals(2, rc.domainEvents().size());
    }

    @Test
    void escalateTransitionsToEscalated() {
        ReconciliationCase rc = ReconciliationCase.open("order-1", "payment-1",
            "late-payment", Money.of("CNY", "200.00"), Money.of("CNY", "180.00"),
            "Late payment with fee", NOW, "cmd-open", "corr-1");
        rc.markInvestigating(NOW.plusSeconds(5), "cmd-investigate", "event-1", "corr-1");
        assertEquals(ReconciliationCase.ReconciliationCaseStatus.INVESTIGATING, rc.status());
        rc.escalate("Manual needed", NOW.plusSeconds(10), "cmd-escalate", "event-2", "corr-1");
        assertEquals(ReconciliationCase.ReconciliationCaseStatus.ESCALATED, rc.status());
    }

    @Test
    void cannotEscalateResolvedCase() {
        ReconciliationCase rc = ReconciliationCase.open("order-1", null, "amount-mismatch",
            Money.of("CNY", "50.00"), Money.of("CNY", "45.00"),
            "Minor", NOW, "cmd-open", "corr-1");
        rc.resolve("auto-resolved", "OK", NOW.plusSeconds(10), "cmd-resolve", "event-1", "corr-1");
        assertThrows(DomainRuleViolation.class, () ->
            rc.escalate("Should not work", NOW.plusSeconds(20), "cmd-escalate", "event-2", "corr-1"));
    }

    @Test
    void eventMetadataIsPopulatedForOpenedEvent() {
        ReconciliationCase rc = ReconciliationCase.open(null, null, "missing-in-channel",
            Money.of("CNY", "100.00"), Money.of("CNY", "0.00"),
            "Missing", NOW, "cmd-open", "corr-1");
        FinanceSettlementEvent event = rc.domainEvents().getFirst();
        assertEquals("ReconciliationCaseOpened", event.eventType());
        assertEquals("cmd-open", event.sourceCommandId());
        assertEquals("corr-1", event.correlationId());
        assertNotNull(event.eventId());
    }
}
