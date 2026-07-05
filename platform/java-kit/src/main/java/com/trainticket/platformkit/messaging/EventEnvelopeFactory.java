package com.trainticket.platformkit.messaging;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;

public final class EventEnvelopeFactory {
    private final String producer;
    private final Clock clock;

    public EventEnvelopeFactory(String producer) { this(producer, Clock.systemUTC()); }
    public EventEnvelopeFactory(String producer, Clock clock) { this.producer = Objects.requireNonNull(producer); this.clock = Objects.requireNonNull(clock); }

    public EventEnvelope create(String eventType, String causationId, String correlationId, Object payload) {
        String canonicalCorrelationId = correlationId == null
            ? UuidV7.correlationId()
            : EventEnvelope.requirePrefixedUuidV7(correlationId, "correlationId", "corr-");
        String canonicalCausationId = causationId == null
            ? UuidV7.commandId()
            : EventEnvelope.requirePrefixedUuidV7(causationId, "causationId", "cmd-", "evt-");
        return new EventEnvelope(
            UuidV7.eventId(), eventType, clock.instant(), canonicalCorrelationId,
            canonicalCausationId, producer, 1, payload == null ? Map.of() : payload
        );
    }

    public static void publishAfterCommit(Runnable commit, EventPublisher publisher, EventEnvelope envelope) {
        Objects.requireNonNull(commit, "commit is required").run();
        Objects.requireNonNull(publisher, "publisher is required").publish(Objects.requireNonNull(envelope, "envelope is required"));
    }

}
