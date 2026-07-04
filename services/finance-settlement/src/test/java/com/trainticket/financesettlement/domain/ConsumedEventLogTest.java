package com.trainticket.financesettlement.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ConsumedEventLogTest {
    private static final Instant NOW = Instant.parse("2026-07-03T12:00:00Z");

    @Test
    void recordsConsumedEvent() {
        ConsumedEventLog log = ConsumedEventLog.record("event-123", "payment", "PaymentCaptured", NOW);
        assertNotNull(log.logId());
        assertEquals("event-123", log.eventId());
        assertEquals("payment", log.source());
        assertEquals("PaymentCaptured", log.eventType());
        assertEquals(NOW, log.consumedAt());
    }

    @Test
    void refusesBlankEventId() {
        assertThrows(DomainRuleViolation.class, () -> ConsumedEventLog.record("", "payment", "PaymentCaptured", NOW));
    }

    @Test
    void refusesNullConsumedAt() {
        assertThrows(NullPointerException.class, () -> ConsumedEventLog.record("event-1", "payment", "PaymentCaptured", null));
    }

    @Test
    void refusesBlankSource() {
        assertThrows(DomainRuleViolation.class, () -> ConsumedEventLog.record("event-1", "", "PaymentCaptured", NOW));
    }

    @Test
    void refusesBlankEventType() {
        assertThrows(DomainRuleViolation.class, () -> ConsumedEventLog.record("event-1", "payment", "", NOW));
    }

    @Test
    void recordsEventsFromDifferentSources() {
        assertEquals("payment", ConsumedEventLog.record("e-1", "payment", "PaymentCaptured", NOW).source());
        assertEquals("journey-order", ConsumedEventLog.record("e-2", "journey-order", "JourneyOrderCreated", NOW).source());
        assertEquals("post-sales", ConsumedEventLog.record("e-3", "post-sales", "PostSalesApplied", NOW).source());
    }
}
