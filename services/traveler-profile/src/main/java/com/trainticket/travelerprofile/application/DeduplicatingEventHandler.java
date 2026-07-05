package com.trainticket.travelerprofile.application;

import java.util.Objects;

public final class DeduplicatingEventHandler implements EventSubscriber.EventHandler {
    private final ConsumedEventLog consumedEventLog;
    private final EventSubscriber.EventHandler delegate;

    public DeduplicatingEventHandler(ConsumedEventLog consumedEventLog, EventSubscriber.EventHandler delegate) {
        this.consumedEventLog = Objects.requireNonNull(consumedEventLog, "consumedEventLog is required");
        this.delegate = Objects.requireNonNull(delegate, "delegate is required");
    }

    @Override
    public EventSubscriber.HandlerResult handle(EventEnvelope envelope) {
        if (!consumedEventLog.recordIfFirstSeen(envelope.eventId())) {
            return EventSubscriber.HandlerResult.SUCCESS;
        }
        return delegate.handle(envelope);
    }
}
