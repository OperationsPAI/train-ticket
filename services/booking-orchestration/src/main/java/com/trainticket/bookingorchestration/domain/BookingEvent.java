package com.trainticket.bookingorchestration.domain;

import java.time.Instant;

/** Coordination facts emitted by the BookingSaga for Journey Order, Capacity, Payment and Entitlement. */
public sealed interface BookingEvent extends DomainEvent permits BookingEvent.BookingSagaStarted,
    BookingEvent.BookingSagaAdvanced,
    BookingEvent.BookingSagaStepSucceeded,
    BookingEvent.BookingSagaStepFailed,
    BookingEvent.BookingSagaRetryScheduled,
    BookingEvent.BookingSagaCompleted,
    BookingEvent.BookingSagaFailed,
    BookingEvent.BookingSagaManualReviewRequired,
    BookingEvent.RiskAssessmentRequested,
    BookingEvent.InvoiceRequested {

    record BookingSagaStarted(String eventId, String aggregateId, Instant occurredAt, String journeyOrderId)
        implements BookingEvent {
    }

    record BookingSagaAdvanced(String eventId, String aggregateId, Instant occurredAt, BookingSagaStatus status)
        implements BookingEvent {
    }

    record BookingSagaStepSucceeded(String eventId, String aggregateId, Instant occurredAt, String stepName,
                                    String idempotencyKey) implements BookingEvent {
    }

    record BookingSagaStepFailed(String eventId, String aggregateId, Instant occurredAt, String stepName,
                                 String idempotencyKey, String reason) implements BookingEvent {
    }

    record BookingSagaRetryScheduled(String eventId, String aggregateId, Instant occurredAt, String stepName,
                                     String idempotencyKey, int attemptNumber) implements BookingEvent {
    }

    record BookingSagaCompleted(String eventId, String aggregateId, Instant occurredAt) implements BookingEvent {
    }

    record BookingSagaFailed(String eventId, String aggregateId, Instant occurredAt, String reason)
        implements BookingEvent {
    }

    record BookingSagaManualReviewRequired(String eventId, String aggregateId, Instant occurredAt, String reason)
        implements BookingEvent {
    }

    record RiskAssessmentRequested(String eventId, String aggregateId, Instant occurredAt,
                                   String journeyOrderId, String accountId)
        implements BookingEvent {
    }

    record InvoiceRequested(String eventId, String aggregateId, Instant occurredAt,
                            String journeyOrderId, String paymentRef)
        implements BookingEvent {
    }
}
