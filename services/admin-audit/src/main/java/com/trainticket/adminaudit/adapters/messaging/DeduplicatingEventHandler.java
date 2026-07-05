package com.trainticket.adminaudit.adapters.messaging;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.adminaudit.application.ports.EventSubscriber;

public class DeduplicatingEventHandler implements EventSubscriber.EventHandler {
    private final ConsumedEventLog consumedEventLog;
    private final EventSubscriber.EventHandler delegate;

    public DeduplicatingEventHandler(ConsumedEventLog consumedEventLog, EventSubscriber.EventHandler delegate) {
        this.consumedEventLog = consumedEventLog;
        this.delegate = delegate;
    }

    @Override
    public EventSubscriber.HandlerResult handle(EventEnvelope envelope) {
        if (consumedEventLog.alreadyProcessed(envelope.eventId())) {
            return EventSubscriber.HandlerResult.SUCCESS;
        }
        EventSubscriber.HandlerResult result = delegate.handle(envelope);
        if (result == EventSubscriber.HandlerResult.SUCCESS) {
            consumedEventLog.recordProcessed(envelope.eventId());
        }
        return result;
    }
}
