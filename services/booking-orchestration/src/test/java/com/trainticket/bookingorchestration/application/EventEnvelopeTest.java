package com.trainticket.bookingorchestration.application;

import com.trainticket.platformkit.messaging.EventEnvelope;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class EventEnvelopeTest {

    @Test
    void createsValidEnvelope() {
        Instant now = Instant.now();
        var envelope = new EventEnvelope("evt-0194f2e0-7b3e-7610-8284-5c26e8b0c222", "BookingSagaStarted", now, "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c444", "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c555", "booking-orchestration", 1, new Object());

        assertEquals("evt-0194f2e0-7b3e-7610-8284-5c26e8b0c222", envelope.eventId());
        assertEquals("BookingSagaStarted", envelope.eventType());
        assertEquals(1, envelope.schemaVersion());
        assertEquals("booking-orchestration", envelope.producer());
        assertEquals(now, envelope.occurredAt());
        assertNotNull(envelope.payload());
    }

    @Test
    void rejectsNullEventId() {
        assertThrows(IllegalArgumentException.class, () -> new EventEnvelope(null, "BookingSagaStarted", Instant.now(), null, null, "booking-orchestration", 1, null));
    }

    @Test
    void rejectsBlankEventType() {
        assertThrows(IllegalArgumentException.class, () -> new EventEnvelope("evt-123", "", Instant.now(), null, null, "booking-orchestration", 1, null));
    }

    @Test
    void rejectsNullProducer() {
        assertThrows(IllegalArgumentException.class, () -> new EventEnvelope("evt-123", "BookingSagaStarted", Instant.now(), null, null, null, 1, null));
    }

    @Test
    void rejectsNullOccurredAt() {
        assertThrows(IllegalArgumentException.class, () -> new EventEnvelope("evt-123", "BookingSagaStarted", null, null, null, "booking-orchestration", 1, null));
    }
}
