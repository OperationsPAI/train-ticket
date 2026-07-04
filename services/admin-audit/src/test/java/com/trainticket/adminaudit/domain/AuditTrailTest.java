package com.trainticket.adminaudit.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditTrailTest {

    @Test
    void recordEntry() {
        AuditTrail trail = new AuditTrail();
        Instant now = Instant.now();
        AuditEntry entry = trail.recordEntry(
            "op-123",
            "Alice",
            "ORDER_CANCELLATION",
            "ord-456",
            "journey-order",
            "CUSTOMER_REQUEST",
            "corr-789",
            "Order cancelled successfully",
            null,
            now,
            "cmd-1"
        );

        assertNotNull(entry);
        assertTrue(entry.entryId().startsWith("aud-"));
        assertEquals("op-123", entry.actorId());
        assertEquals("Alice", entry.actorDisplayName());
        assertEquals("ORDER_CANCELLATION", entry.actionType());
        assertEquals("ord-456", entry.resourceRef());
        assertEquals("journey-order", entry.resourceDomain());
        assertEquals("CUSTOMER_REQUEST", entry.reasonCode());
        assertEquals("corr-789", entry.correlationId());
        assertEquals("Order cancelled successfully", entry.resultSummary());
        assertNull(entry.correctedEntryId());
        assertEquals(now, entry.recordedAt());
        assertEquals(1, trail.size());
    }

    @Test
    void recordCorrectionEntry() {
        AuditTrail trail = new AuditTrail();
        Instant now = Instant.now();
        AuditEntry original = trail.recordEntry(
            "op-123", "Alice", "ORDER_CANCELLATION", "ord-456",
            "journey-order", "CUSTOMER_REQUEST", "corr-789",
            "Original result", null, now, "cmd-1"
        );

        AuditEntry correction = trail.recordEntry(
            "op-456", "Bob", "ORDER_CANCELLATION_CORRECTION", "ord-456",
            "journey-order", "DATA_CORRECTION", "corr-789",
            "Corrected result", original.entryId(),
            now.plusSeconds(3600), "cmd-2"
        );

        assertTrue(correction.isCorrection());
        assertEquals(original.entryId(), correction.correctedEntryId());
        assertEquals(2, trail.size());
    }

    @Test
    void correctionReferencesExistingEntry() {
        AuditTrail trail = new AuditTrail();
        Instant now = Instant.now();

        DomainRuleViolation ex = assertThrows(DomainRuleViolation.class,
            () -> trail.recordEntry(
                "op-123", "Alice", "CORRECTION", "ord-456",
                "journey-order", "DATA_CORRECTION", "corr-789",
                "Result", "aud-nonexistent", now, "cmd-1"));
        assertTrue(ex.getMessage().contains("not found in audit trail"));
    }

    @Test
    void entriesAreAppendOnly() {
        AuditTrail trail = new AuditTrail();
        Instant now = Instant.now();
        trail.recordEntry("op-1", "Alice", "ACTION_A", "res-1", "domain-1", "REASON_A", "corr-1", "OK", null, now, "cmd-1");
        trail.recordEntry("op-2", "Bob", "ACTION_B", "res-2", "domain-2", "REASON_B", "corr-2", "OK", null, now.plusSeconds(10), "cmd-2");
        trail.recordEntry("op-1", "Alice", "ACTION_C", "res-3", "domain-1", "REASON_C", "corr-3", "OK", null, now.plusSeconds(20), "cmd-3");

        assertEquals(3, trail.size());
        assertEquals("ACTION_A", trail.entries().get(0).actionType());
        assertEquals("ACTION_B", trail.entries().get(1).actionType());
        assertEquals("ACTION_C", trail.entries().get(2).actionType());
    }

    @Test
    void entriesListIsUnmodifiable() {
        AuditTrail trail = new AuditTrail();
        Instant now = Instant.now();
        trail.recordEntry("op-1", "Alice", "ACTION_A", "res-1", "domain-1", "REASON_A", "corr-1", "OK", null, now, "cmd-1");

        assertThrows(UnsupportedOperationException.class,
            () -> trail.entries().clear());
    }

    @Test
    void entryValidationRequiresActorId() {
        AuditTrail trail = new AuditTrail();
        Instant now = Instant.now();

        assertThrows(NullPointerException.class,
            () -> trail.recordEntry(null, "Alice", "ACTION", "res-1", "domain-1", "REASON", "corr-1", "OK", null, now, "cmd-1"));
    }

    @Test
    void entryValidationRequiresActionType() {
        AuditTrail trail = new AuditTrail();
        Instant now = Instant.now();

        DomainRuleViolation ex = assertThrows(DomainRuleViolation.class,
            () -> trail.recordEntry("op-1", "Alice", "", "res-1", "domain-1", "REASON", "corr-1", "OK", null, now, "cmd-1"));
        assertTrue(ex.getMessage().contains("must not be blank"));
    }

    @Test
    void recordWithNullResultSummaryDefaultsToEmpty() {
        AuditTrail trail = new AuditTrail();
        Instant now = Instant.now();
        AuditEntry entry = trail.recordEntry(
            "op-1", "Alice", "ACTION", "res-1", "domain-1", "REASON", "corr-1",
            null, null, now, "cmd-1"
        );
        assertEquals("", entry.resultSummary());
    }
}
