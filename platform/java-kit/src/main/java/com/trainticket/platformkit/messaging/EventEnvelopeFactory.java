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
        return new EventEnvelope(UuidV7.eventId(), eventType, clock.instant(), canonicalCorrelationId(correlationId), canonicalCausationId(causationId), producer, 1, payload == null ? Map.of() : payload);
    }

    public static void publishAfterCommit(Runnable commit, EventPublisher publisher, EventEnvelope envelope) {
        Objects.requireNonNull(commit, "commit is required").run();
        Objects.requireNonNull(publisher, "publisher is required").publish(Objects.requireNonNull(envelope, "envelope is required"));
    }

    private static String canonicalCorrelationId(String value) { return value != null && value.startsWith("corr-") ? value : UuidV7.correlationId(); }
    private static String canonicalCausationId(String value) { return value != null && (value.startsWith("cmd-") || value.startsWith("evt-")) ? value : UuidV7.commandId(); }
}
