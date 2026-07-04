package com.trainticket.financesettlement.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SettlementViewTest {
    private static final Instant NOW = Instant.parse("2026-07-03T12:00:00Z");

    @Test
    void rebuildsRevenueView() {
        SettlementView view = SettlementView.rebuild("REVENUE", 1, 42, NOW, NOW, "cmd-rebuild", "corr-1");
        assertNotNull(view.settlementViewId());
        assertEquals("REVENUE", view.viewType());
        assertEquals(1, view.version());
        assertEquals(42, view.eventCount());
        assertEquals(1, view.domainEvents().size());
        assertInstanceOf(SettlementViewRebuilt.class, view.domainEvents().getFirst());
    }

    @Test
    void rebuildsReconciliationView() {
        SettlementView view = SettlementView.rebuild("RECONCILIATION", 3, 128, NOW, NOW, "cmd-rebuild", "corr-1");
        assertEquals("RECONCILIATION", view.viewType());
        assertEquals(3, view.version());
        assertEquals(128, view.eventCount());
    }

    @Test
    void rebuildsSettlementView() {
        SettlementView view = SettlementView.rebuild("SETTLEMENT", 1, 0, NOW, NOW, "cmd-rebuild", "corr-1");
        assertEquals(0, view.eventCount());
    }

    @Test
    void refusesNegativeEventCount() {
        assertThrows(DomainRuleViolation.class, () ->
            SettlementView.rebuild("REVENUE", 1, -1, NOW, NOW, "cmd-rebuild", "corr-1"));
    }

    @Test
    void refusesZeroVersion() {
        assertThrows(DomainRuleViolation.class, () ->
            SettlementView.rebuild("REVENUE", 0, 10, NOW, NOW, "cmd-rebuild", "corr-1"));
    }

    @Test
    void eventMetadataIsPopulated() {
        SettlementView view = SettlementView.rebuild("REVENUE", 2, 55, NOW, NOW, "cmd-rebuild", "corr-1");
        FinanceSettlementEvent event = view.domainEvents().getFirst();
        assertEquals("SettlementViewRebuilt", event.eventType());
        assertEquals("cmd-rebuild", event.sourceCommandId());
        assertEquals("corr-1", event.correlationId());
        assertEquals(1, event.schemaVersion());
        assertNotNull(event.eventId());
    }
}
