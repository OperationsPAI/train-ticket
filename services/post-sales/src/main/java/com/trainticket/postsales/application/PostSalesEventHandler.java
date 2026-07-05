package com.trainticket.postsales.application;

import org.springframework.stereotype.Service;

@Service
public class PostSalesEventHandler {
    private final ConsumedEventLog consumedEventLog;

    public PostSalesEventHandler(ConsumedEventLog consumedEventLog) {
        this.consumedEventLog = consumedEventLog;
    }

    public EventSubscriber.HandlerResult handle(EventEnvelope envelope) {
        if (!consumedEventLog.recordIfFirstSeen(envelope.eventId())) {
            return EventSubscriber.HandlerResult.SUCCESS;
        }
        return EventSubscriber.HandlerResult.SUCCESS;
    }
}
