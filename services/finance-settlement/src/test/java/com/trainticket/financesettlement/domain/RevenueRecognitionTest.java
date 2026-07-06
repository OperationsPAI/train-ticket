package com.trainticket.financesettlement.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RevenueRecognitionTest {
    private static final Instant NOW = Instant.parse("2026-07-03T12:00:00Z");

    @Test
    void recognizesRevenueForCompletedOrderItem() {
        Money amount = Money.of("CNY", "120.00");
        RevenueRecognition recognition = RevenueRecognition.recognize(
            "order-1", "item-1", "fare", amount, "policy-v1",
            "source-event-1", NOW, NOW, "cmd-recognize", "corr-1");
        assertNotNull(recognition.revenueRecognitionId());
        assertEquals("order-1", recognition.orderId());
        assertEquals("item-1", recognition.orderItemId());
        assertEquals("fare", recognition.componentCode());
        assertEquals(amount, recognition.amount());
        assertEquals("policy-v1", recognition.recognitionPolicyVersion());
        assertEquals("source-event-1", recognition.sourceEventId());
        assertFalse(recognition.reversed());
        assertEquals(1, recognition.domainEvents().size());
        assertInstanceOf(RevenueRecognized.class, recognition.domainEvents().getFirst());
    }

    @Test
    void refusesNegativeAmountForNonDiscountComponent() {
        assertThrows(DomainRuleViolation.class, () -> RevenueRecognition.recognize(
            "order-1", "item-1", "fare", Money.of("CNY", "-10.00"),
            "policy-v1", "source-event-1", NOW, NOW, "cmd-recognize", "corr-1"));
    }

    @Test
    void refusesZeroAmount() {
        assertThrows(DomainRuleViolation.class, () -> RevenueRecognition.recognize(
            "order-1", "item-1", "fare", Money.of("CNY", "0.00"),
            "policy-v1", "source-event-1", NOW, NOW, "cmd-recognize", "corr-1"));
    }

    @Test
    void reverseCreatesOffsettingEvent() {
        Money amount = Money.of("CNY", "120.00");
        RevenueRecognition recognition = RevenueRecognition.recognize(
            "order-1", "item-1", "fare", amount, "policy-v1",
            "source-event-1", NOW, NOW, "cmd-recognize", "corr-1");
        recognition.reverse("order cancelled", NOW.plusSeconds(100), "cmd-reverse", "event-1", "corr-1");
        assertTrue(recognition.reversed());
        assertEquals("order cancelled", recognition.reversalReason());
        assertEquals(2, recognition.domainEvents().size());
        RevenueRecognitionReversed reversalEvent = (RevenueRecognitionReversed) recognition.domainEvents().get(1);
        assertEquals(amount, reversalEvent.amount());
        assertEquals("fare", reversalEvent.componentCode());
        assertEquals("order cancelled", reversalEvent.reversalReason());
    }

    @Test
    void reverseIsIdempotent() {
        RevenueRecognition recognition = RevenueRecognition.recognize(
            "order-1", "item-1", "fare", Money.of("CNY", "120.00"), "policy-v1",
            "source-event-1", NOW, NOW, "cmd-recognize", "corr-1");
        recognition.reverse("reason", NOW.plusSeconds(100), "cmd-reverse", "event-1", "corr-1");
        recognition.reverse("reason again", NOW.plusSeconds(200), "cmd-reverse2", "event-2", "corr-1");
        assertEquals(2, recognition.domainEvents().size());
    }

    @Test
    void eventMetadataIsPopulatedCorrectly() {
        RevenueRecognition recognition = RevenueRecognition.recognize(
            "order-1", "item-1", "ancillary", Money.of("CNY", "25.00"), "policy-v2",
            "source-event-2", NOW, NOW, "cmd-recognize", "corr-1");
        FinanceSettlementEvent event = recognition.domainEvents().getFirst();
        assertEquals("RevenueRecognized", event.eventType());
        assertEquals(1, event.schemaVersion());
        assertEquals("cmd-recognize", event.sourceCommandId());
        assertEquals("corr-1", event.correlationId());
        assertNotNull(event.eventId());
    }

    @Test
    void recognizesDiscountWithNegativeAmount() {
        RevenueRecognition recognition = RevenueRecognition.recognize(
            "order-1", "item-discount", "discount", Money.of("CNY", "-20.00"), "policy-v1",
            "source-event-3", NOW, NOW, "cmd-recognize", "corr-1");
        assertTrue(recognition.amount().isNegative());
        assertEquals("discount", recognition.componentCode());
    }
}
