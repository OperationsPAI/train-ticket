package com.trainticket.journeyorder.application.port.out;

import com.trainticket.journeyorder.domain.EventEnvelope;

/**
 * Handles upstream events consumed by the journey-order context.
 * Implementations must be idempotent by eventId.
 */
@FunctionalInterface
public interface JourneyOrderEventHandler {
    EventSubscriber.HandlerResult handle(EventEnvelope envelope);
}
