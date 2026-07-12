package com.trainticket.bookingorchestration.domain;

import java.time.Instant;

/**
 * Marker for facts emitted by Booking Orchestration. These facts are integration-safe:
 * they are intended for other domains to consume instead of mutating Booking state directly.
 */
public sealed interface DomainEvent permits BookingEvent, SegmentBookingEvent {
    String eventId();

    String aggregateId();

    Instant occurredAt();
}
