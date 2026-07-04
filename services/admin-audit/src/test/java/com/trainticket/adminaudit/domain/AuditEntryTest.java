package com.trainticket.adminaudit.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AuditEntryTest {

    @Test
    void createAuditEntry() {
        Instant now = Instant.now();
        AuditEntry entry = new AuditEntry(
            "aud-123", "op-1", "Alice", "ORDER_CREATE",
            "ord-456", "journey-order", "PURCHASE", "corr-789",
            "Order created", null, now
        );
        assertEquals("aud-123", entry.entryId());
        assertEquals("op-1", entry.actorId());
        assertEquals("Alice", entry.actorDisplayName());
        assertFalse(entry.isCorrection());
    }

    @Test
    void correctionEntry() {
        Instant now = Instant.now();
        AuditEntry entry = new AuditEntry(
            "aud-456", "op-2", "Bob", "CORRECTION",
            "ord-456", "journey-order", "DATA_CORRECTION", "corr-789",
            "Corrected", "aud-123", now
        );
        assertTrue(entry.isCorrection());
        assertEquals("aud-123", entry.correctedEntryId());
    }

    @Test
    void blankCorrectedEntryIdBecomesNull() {
        Instant now = Instant.now();
        AuditEntry entry = new AuditEntry(
            "aud-456", "op-2", "Bob", "CORRECTION",
            "ord-456", "journey-order", "DATA_CORRECTION", "corr-789",
            "Corrected", "  ", now
        );
        assertNull(entry.correctedEntryId());
        assertFalse(entry.isCorrection());
    }

    @Test
    void nullResultSummaryDefaultsToEmpty() {
        Instant now = Instant.now();
        AuditEntry entry = new AuditEntry(
            "aud-123", "op-1", "Alice", "ACTION",
            "res-1", "domain-1", "REASON", "corr-1",
            null, null, now
        );
        assertEquals("", entry.resultSummary());
    }

    @Test
    void entryIdMustNotBeBlank() {
        Instant now = Instant.now();
        assertThrows(DomainRuleViolation.class,
            () -> new AuditEntry("", "op-1", "Alice", "ACTION", "res-1", "domain-1", "REASON", "corr-1", "OK", null, now));
    }

    @Test
    void actorIdMustNotBeBlank() {
        Instant now = Instant.now();
        assertThrows(DomainRuleViolation.class,
            () -> new AuditEntry("aud-1", "", "Alice", "ACTION", "res-1", "domain-1", "REASON", "corr-1", "OK", null, now));
    }
}
