package com.trainticket.bookingorchestration.application;

import com.trainticket.platformkit.messaging.EventEnvelope;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class EventEnvelopeTest {

    @Test
    void createsValidEnvelope() {
        Instant now = Instant.now();
        var envelope = new EventEnvelope(
            "evt-0194f2e0-7b3e-7610-0284-5c26e8b0c222",
            "BookingSagaStarted", 1, "booking-orchestration",
            "cmd-0194f2e0-7b3e-7610-0284-5c26e8b0c555",
            "corr-0194f2e0-7b3e-7610-0284-5c26e8b0c444", now, new Object());

        assertEquals("evt-0194f2e0-7b3e-7610-0284-5c26e8b0c222", envelope.eventId());
        assertEquals("BookingSagaStarted", envelope.eventType());
        assertEquals(1, envelope.schemaVersion());
        assertEquals("booking-orchestration", envelope.producer());
        assertEquals(now, envelope.occurredAt());
        assertNotNull(envelope.payload());
    }

    @Test
    void rejectsNullEventId() {
        assertThrows(IllegalArgumentException.class, () -> new EventEnvelope(
            null, "BookingSagaStarted", 1, "booking-orchestration",
            null, null, Instant.now(), null));
    }

    @Test
    void rejectsBlankEventType() {
        assertThrows(IllegalArgumentException.class, () -> new EventEnvelope(
            "evt-123", "", 1, "booking-orchestration",
            null, null, Instant.now(), null));
    }

    @Test
    void rejectsNullProducer() {
        assertThrows(IllegalArgumentException.class, () -> new EventEnvelope(
            "evt-123", "BookingSagaStarted", 1, null,
            null, null, Instant.now(), null));
    }

    @Test
    void rejectsNullOccurredAt() {
        assertThrows(IllegalArgumentException.class, () -> new EventEnvelope(
            "evt-123", "BookingSagaStarted", 1, "booking-orchestration",
            null, null, null, null));
    }
}
