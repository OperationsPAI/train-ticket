package com.trainticket.payment.application;

import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class PaymentInboundEventHandler implements EventSubscriber.EventHandler {
    private final ConsumedEventDeduplicator deduplicator;

    public PaymentInboundEventHandler(ConsumedEventDeduplicator deduplicator) {
        this.deduplicator = Objects.requireNonNull(deduplicator, "deduplicator is required");
    }

    @Override
    public HandlerResult handle(EventEnvelope envelope) {
        if (!deduplicator.recordIfNew(envelope.eventId())) {
            return HandlerResult.SUCCESS;
        }
        return HandlerResult.SUCCESS;
    }
}
