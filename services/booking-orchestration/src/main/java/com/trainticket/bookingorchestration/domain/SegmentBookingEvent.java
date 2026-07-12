package com.trainticket.bookingorchestration.domain;

import java.time.Instant;
import java.util.Optional;

/** Segment-level facts emitted for Journey Order, Capacity, Payment, Entitlement and support read models. */
public sealed interface SegmentBookingEvent extends DomainEvent permits SegmentBookingEvent.SegmentReservationRequested,
    SegmentBookingEvent.SegmentCapacityHolding,
    SegmentBookingEvent.SegmentReservationConfirmed,
    SegmentBookingEvent.SegmentReservationFailed,
    SegmentBookingEvent.ProviderReferenceAttached,
    SegmentBookingEvent.ProviderReservationTimedOut,
    SegmentBookingEvent.SegmentTicketed,
    SegmentBookingEvent.SegmentBookingCancelRequested,
    SegmentBookingEvent.SegmentBookingCancelled,
    SegmentBookingEvent.ProviderConfirmationReceivedAfterCancellation,
    SegmentBookingEvent.ProviderCancellationRequired,
    SegmentBookingEvent.SeatAllocationRequested {

    record SegmentReservationRequested(String eventId, String aggregateId, Instant occurredAt, String journeyOrderId,
                                       String segmentRef, String travelerRef, String idempotencyKey)
        implements SegmentBookingEvent {
    }

    record SegmentCapacityHolding(String eventId, String aggregateId, Instant occurredAt, String capacityHoldId)
        implements SegmentBookingEvent {
    }

    record SegmentReservationConfirmed(String eventId, String aggregateId, Instant occurredAt,
                                        Optional<ProviderReference> providerReference, String evidence)
        implements SegmentBookingEvent {
    }

    record SegmentReservationFailed(String eventId, String aggregateId, Instant occurredAt, String reason)
        implements SegmentBookingEvent {
    }

    record ProviderReferenceAttached(String eventId, String aggregateId, Instant occurredAt,
                                     ProviderReference providerReference) implements SegmentBookingEvent {
    }

    record ProviderReservationTimedOut(String eventId, String aggregateId, Instant occurredAt, String reason)
        implements SegmentBookingEvent {
    }

    record SegmentTicketed(String eventId, String aggregateId, Instant occurredAt, String entitlementId)
        implements SegmentBookingEvent {
    }

    record SegmentBookingCancelRequested(String eventId, String aggregateId, Instant occurredAt, String reason)
        implements SegmentBookingEvent {
    }

    record SegmentBookingCancelled(String eventId, String aggregateId, Instant occurredAt, String reason)
        implements SegmentBookingEvent {
    }

    record ProviderConfirmationReceivedAfterCancellation(String eventId, String aggregateId, Instant occurredAt,
                                                         ProviderReference providerReference, String reason)
        implements SegmentBookingEvent {
    }

    record ProviderCancellationRequired(String eventId, String aggregateId, Instant occurredAt,
                                        ProviderReference providerReference, String reason)
        implements SegmentBookingEvent {
    }

    record SeatAllocationRequested(String eventId, String aggregateId, Instant occurredAt,
                                   String sagaId, String segmentRef, String travelerRef)
        implements SegmentBookingEvent {
    }
}
