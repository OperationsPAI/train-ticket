package com.trainticket.platformkit.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventEnvelopeFactoryTest {
    @Test
    void createsStrictCanonicalEnvelope() throws Exception {
        EventEnvelopeFactory factory = new EventEnvelopeFactory("payment", Clock.fixed(Instant.parse("2026-07-05T10:30:00Z"), ZoneOffset.UTC));
        String correlationId = PrefixedIds.newCorrelationId();
        String causationId = PrefixedIds.newCommandId();

        EventEnvelope envelope = factory.create("PaymentCaptured", correlationId, causationId, Map.of("paymentIntentId", "pi-1"));

        assertThat(PrefixedIds.isEventId(envelope.eventId())).isTrue();
        assertThat(envelope.correlationId()).isEqualTo(correlationId);
        assertThat(envelope.causationId()).isEqualTo(causationId);
        assertThat(envelope.occurredAt()).isEqualTo(Instant.parse("2026-07-05T10:30:00Z"));
        String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(envelope);
        assertThat(new ObjectMapper().readValue(json, Map.class).keySet())
            .containsExactlyInAnyOrder("eventId", "eventType", "occurredAt", "correlationId", "causationId", "producer", "schemaVersion", "payload");
    }

    @Test
    void rejectsInvalidProvidedIdsInsteadOfRegenerating() {
        EventEnvelopeFactory factory = new EventEnvelopeFactory("payment");
        assertThatThrownBy(() -> factory.create("PaymentCaptured", "corr-not-a-uuid", PrefixedIds.newCommandId(), Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("correlationId");
        assertThatThrownBy(() -> factory.create("PaymentCaptured", PrefixedIds.newCorrelationId(), "cmd-not-a-uuid", Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("causationId");
        assertThatThrownBy(() -> new EventEnvelope("evt-not-a-uuid", "Type", Instant.now(), PrefixedIds.newCorrelationId(), PrefixedIds.newCommandId(), "producer", 1, Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("eventId");
    }
}
