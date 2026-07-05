package com.trainticket.platformkit.messaging;

import java.time.Clock;
import java.util.Objects;

public class EventEnvelopeFactory {
    private final Clock clock;
    private final String producer;

    public EventEnvelopeFactory(String producer) {
        this(producer, Clock.systemUTC());
    }

    public EventEnvelopeFactory(String producer, Clock clock) {
        this.producer = requireText(producer, "producer");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public EventEnvelope create(String eventType, Object payload) {
        return create(eventType, PrefixedIds.newCorrelationId(), PrefixedIds.newCommandId(), payload);
    }

    public EventEnvelope create(String eventType, String correlationId, String causationId, Object payload) {
        PrefixedIds.requireCorrelationId(correlationId);
        PrefixedIds.requireCausationId(causationId);
        return new EventEnvelope(
            PrefixedIds.newEventId(),
            eventType,
            clock.instant(),
            correlationId,
            causationId,
            producer,
            1,
            payload
        );
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
