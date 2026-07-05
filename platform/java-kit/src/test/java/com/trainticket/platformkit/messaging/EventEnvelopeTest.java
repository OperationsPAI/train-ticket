package com.trainticket.platformkit.messaging;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventEnvelopeTest {
    @Test
    void rejectsPrefixedNonUuidV7Ids() {
        assertThrows(IllegalArgumentException.class, () -> new EventEnvelope(
            "evt-not-a-uuid", "TestEvent", Instant.parse("2026-01-01T00:00:00Z"),
            UuidV7.correlationId(), UuidV7.commandId(), "test", 1, Map.of()
        ));
        assertThrows(IllegalArgumentException.class, () -> new EventEnvelope(
            UuidV7.eventId(), "TestEvent", Instant.parse("2026-01-01T00:00:00Z"),
            "corr-not-a-uuid", UuidV7.commandId(), "test", 1, Map.of()
        ));
        assertThrows(IllegalArgumentException.class, () -> new EventEnvelope(
            UuidV7.eventId(), "TestEvent", Instant.parse("2026-01-01T00:00:00Z"),
            UuidV7.correlationId(), "cmd-not-a-uuid", "test", 1, Map.of()
        ));
    }

    @Test
    void acceptsGeneratedPrefixedUuidV7Ids() {
        assertDoesNotThrow(() -> new EventEnvelope(
            UuidV7.eventId(), "TestEvent", Instant.parse("2026-01-01T00:00:00Z"),
            UuidV7.correlationId(), UuidV7.commandId(), "test", 1, Map.of()
        ));
    }

    @Test
    void factoryRejectsInvalidCallerProvidedCorrelationAndCausationIds() {
        EventEnvelopeFactory factory = new EventEnvelopeFactory("test");
        assertThrows(IllegalArgumentException.class, () -> factory.create("TestEvent", UuidV7.commandId(), "corr-invalid", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> factory.create("TestEvent", "cmd-invalid", UuidV7.correlationId(), Map.of()));
    }
}
