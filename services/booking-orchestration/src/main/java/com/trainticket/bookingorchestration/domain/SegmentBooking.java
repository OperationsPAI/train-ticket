package com.trainticket.bookingorchestration.domain;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import static com.trainticket.bookingorchestration.domain.SegmentBookingEvent.ProviderCancellationRequired;
import static com.trainticket.bookingorchestration.domain.SegmentBookingEvent.ProviderConfirmationReceivedAfterCancellation;
import static com.trainticket.bookingorchestration.domain.SegmentBookingEvent.ProviderReferenceAttached;
import static com.trainticket.bookingorchestration.domain.SegmentBookingEvent.ProviderReservationTimedOut;
import static com.trainticket.bookingorchestration.domain.SegmentBookingEvent.SegmentBookingCancelRequested;
import static com.trainticket.bookingorchestration.domain.SegmentBookingEvent.SegmentBookingCancelled;
import static com.trainticket.bookingorchestration.domain.SegmentBookingEvent.SegmentCapacityHolding;
import static com.trainticket.bookingorchestration.domain.SegmentBookingEvent.SegmentReservationConfirmed;
import static com.trainticket.bookingorchestration.domain.SegmentBookingEvent.SegmentReservationFailed;
import static com.trainticket.bookingorchestration.domain.SegmentBookingEvent.SegmentReservationRequested;
import static com.trainticket.bookingorchestration.domain.SegmentBookingEvent.SegmentTicketed;

public final class SegmentBooking {
    private final String segmentBookingId;
    private final String journeyOrderId;
    private final String offerItemRef;
    private final String segmentRef;
    private final String travelerRef;
    private final String bookingPurpose;
    private final String idempotencyKey;
    private final EventRecorder eventRecorder;
    private SegmentBookingStatus status;
    private String capacityHoldId;
    private ProviderReference providerReference;
    private String entitlementId;
    private String failureReason;
    private String cancellationReason;
    private long version;

    private SegmentBooking(String segmentBookingId, String journeyOrderId, String offerItemRef, String segmentRef,
                           String travelerRef, String bookingPurpose, Clock clock) {
        this.segmentBookingId = requireText(segmentBookingId, "segmentBookingId");
        this.journeyOrderId = requireText(journeyOrderId, "journeyOrderId");
        this.offerItemRef = requireText(offerItemRef, "offerItemRef");
        this.segmentRef = requireText(segmentRef, "segmentRef");
        this.travelerRef = requireText(travelerRef, "travelerRef");
        this.bookingPurpose = requireText(bookingPurpose, "bookingPurpose");
        this.idempotencyKey = journeyOrderId + ":" + segmentRef + ":" + travelerRef + ":" + bookingPurpose;
        this.eventRecorder = new EventRecorder(clock);
    }

    public static SegmentBooking requestReservation(String segmentBookingId, String journeyOrderId, String offerItemRef,
                                                    String segmentRef, String travelerRef, String bookingPurpose,
                                                    Clock clock) {
        SegmentBooking booking = new SegmentBooking(segmentBookingId, journeyOrderId, offerItemRef, segmentRef,
            travelerRef, bookingPurpose, clock);
        booking.status = SegmentBookingStatus.REQUESTED;
        booking.record(new SegmentReservationRequested(booking.nextEventId(), booking.segmentBookingId, booking.now(),
            booking.journeyOrderId, booking.segmentRef, booking.travelerRef, booking.idempotencyKey));
        return booking;
    }

    public static SegmentBooking rehydrate(String segmentBookingId, String journeyOrderId, String offerItemRef,
                                           String segmentRef, String travelerRef, String bookingPurpose,
                                           SegmentBookingStatus status, String capacityHoldId,
                                           ProviderReference providerReference, String entitlementId,
                                           String failureReason, String cancellationReason, Clock clock) {
        SegmentBooking booking = new SegmentBooking(segmentBookingId, journeyOrderId, offerItemRef, segmentRef,
            travelerRef, bookingPurpose, clock);
        booking.status = java.util.Objects.requireNonNull(status, "status is required");
        booking.capacityHoldId = capacityHoldId;
        booking.providerReference = providerReference;
        booking.entitlementId = entitlementId;
        booking.failureReason = failureReason;
        booking.cancellationReason = cancellationReason;
        return booking;
    }

    public SegmentBooking withVersion(long version) {
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        this.version = version;
        return this;
    }

    public String segmentBookingId() {
        return segmentBookingId;
    }

    public String journeyOrderId() {
        return journeyOrderId;
    }

    public String offerItemRef() {
        return offerItemRef;
    }

    public String segmentRef() {
        return segmentRef;
    }

    public String travelerRef() {
        return travelerRef;
    }

    public String bookingPurpose() {
        return bookingPurpose;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public SegmentBookingStatus status() {
        return status;
    }

    public long version() {
        return version;
    }

    public Optional<String> capacityHoldId() {
        return Optional.ofNullable(capacityHoldId);
    }

    public Optional<ProviderReference> providerReference() {
        return Optional.ofNullable(providerReference);
    }

    public Optional<String> entitlementId() {
        return Optional.ofNullable(entitlementId);
    }

    public Optional<String> failureReason() {
        return Optional.ofNullable(failureReason);
    }

    public Optional<String> cancellationReason() {
        return Optional.ofNullable(cancellationReason);
    }

    public void markCapacityHolding(String capacityHoldId) {
        // CapacityHeld and ProviderReservationConfirmed race on separate
        // streams; a hold arriving after confirmation still records the hold
        // reference (needed to release capacity later) without regressing state.
        this.capacityHoldId = requireText(capacityHoldId, "capacityHoldId");
        if (status == SegmentBookingStatus.REQUESTED) {
            status = SegmentBookingStatus.HOLDING;
            record(new SegmentCapacityHolding(nextEventId(), segmentBookingId, now(), capacityHoldId));
        }
    }

    public void confirmFromProvider(ProviderReservationConfirmed confirmation) {
        if (!segmentBookingId.equals(confirmation.segmentBookingId())) {
            throw new IllegalArgumentException("provider confirmation belongs to a different segment booking");
        }
        if (isTerminalOrCancelling()) {
            registerLateProviderConfirmation(confirmation.providerReference());
            return;
        }
        confirmReservation(Optional.of(confirmation.providerReference()), confirmation.normalizedEvidence());
    }

    public void confirmInternal(String evidence) {
        confirmReservation(Optional.empty(), evidence);
    }

    public void markProviderReservationTimeout(String reason) {
        requireStatus(SegmentBookingStatus.REQUESTED, SegmentBookingStatus.HOLDING);
        record(new ProviderReservationTimedOut(nextEventId(), segmentBookingId, now(), requireText(reason, "reason")));
    }

    public void registerLateProviderConfirmation(ProviderReference requestedProviderReference) {
        if (providerReference != null) {
            return;
        }
        attachSameProviderReferenceOrThrow(requestedProviderReference);
        if (hasProviderCancellationRequiredEvent()) {
            return;
        }
        record(new ProviderConfirmationReceivedAfterCancellation(nextEventId(), segmentBookingId, now(),
            requestedProviderReference, "provider confirmed after booking terminal"));
        record(new ProviderCancellationRequired(nextEventId(), segmentBookingId, now(),
            requestedProviderReference, "cancel late provider confirmation"));
    }

    public void failReservation(String reason) {
        requireStatus(SegmentBookingStatus.REQUESTED, SegmentBookingStatus.HOLDING, SegmentBookingStatus.CANCEL_REQUESTED);
        this.status = SegmentBookingStatus.FAILED;
        this.failureReason = requireText(reason, "reason");
        record(new SegmentReservationFailed(nextEventId(), segmentBookingId, now(), failureReason));
    }

    public void markTicketed(String entitlementId) {
        requireStatus(SegmentBookingStatus.CONFIRMED, SegmentBookingStatus.TICKETED);
        String requestedEntitlement = requireText(entitlementId, "entitlementId");
        if (status == SegmentBookingStatus.TICKETED) {
            if (!requestedEntitlement.equals(this.entitlementId)) {
                throw new IllegalStateException("segment booking is already ticketed with a different entitlement");
            }
            return;
        }
        this.entitlementId = requestedEntitlement;
        this.status = SegmentBookingStatus.TICKETED;
        record(new SegmentTicketed(nextEventId(), segmentBookingId, now(), requestedEntitlement));
    }

    public void requestCancellation(String reason) {
        requireStatus(SegmentBookingStatus.REQUESTED, SegmentBookingStatus.HOLDING, SegmentBookingStatus.CONFIRMED,
            SegmentBookingStatus.TICKETED, SegmentBookingStatus.CANCEL_REQUESTED);
        String requestedReason = requireText(reason, "reason");
        if (status == SegmentBookingStatus.CANCEL_REQUESTED) {
            return;
        }
        this.status = SegmentBookingStatus.CANCEL_REQUESTED;
        this.cancellationReason = requestedReason;
        record(new SegmentBookingCancelRequested(nextEventId(), segmentBookingId, now(), requestedReason));
    }

    public void markCancelled(String reason) {
        requireStatus(SegmentBookingStatus.CANCEL_REQUESTED, SegmentBookingStatus.REQUESTED, SegmentBookingStatus.HOLDING);
        String requestedReason = requireText(reason, "reason");
        if (status == SegmentBookingStatus.CANCELLED) {
            return;
        }
        this.status = SegmentBookingStatus.CANCELLED;
        this.cancellationReason = requestedReason;
        record(new SegmentBookingCancelled(nextEventId(), segmentBookingId, now(), requestedReason));
    }

    public List<DomainEvent> pullEvents() {
        return eventRecorder.pullEvents();
    }

    public List<DomainEvent> peekEvents() {
        return eventRecorder.peekEvents();
    }

    private boolean isTerminalOrCancelling() {
        return status == SegmentBookingStatus.CANCEL_REQUESTED
            || status == SegmentBookingStatus.CANCELLED
            || status == SegmentBookingStatus.FAILED;
    }

    private boolean hasProviderCancellationRequiredEvent() {
        return eventRecorder.peekEvents().stream()
            .anyMatch(ProviderCancellationRequired.class::isInstance);
    }

    private void confirmReservation(Optional<ProviderReference> maybeProviderReference, String evidence) {
        requireStatus(SegmentBookingStatus.REQUESTED, SegmentBookingStatus.HOLDING, SegmentBookingStatus.CONFIRMED);
        String normalizedEvidence = requireText(evidence, "evidence");
        maybeProviderReference.ifPresent(this::attachSameProviderReferenceOrThrow);
        if (status == SegmentBookingStatus.CONFIRMED) {
            return;
        }
        this.status = SegmentBookingStatus.CONFIRMED;
        this.failureReason = null;
        record(new SegmentReservationConfirmed(nextEventId(), segmentBookingId, now(), maybeProviderReference,
            normalizedEvidence));
    }

    private void attachSameProviderReferenceOrThrow(ProviderReference requestedProviderReference) {
        if (providerReference != null && !providerReference.equals(requestedProviderReference)) {
            throw new IllegalStateException("segment booking already has a different provider reference");
        }
        if (providerReference == null) {
            providerReference = requestedProviderReference;
            record(new ProviderReferenceAttached(nextEventId(), segmentBookingId, now(), requestedProviderReference));
        }
    }

    private void requireStatus(SegmentBookingStatus... allowed) {
        for (SegmentBookingStatus allowedStatus : allowed) {
            if (status == allowedStatus) {
                return;
            }
        }
        throw new IllegalStateException("segment booking status " + status + " does not allow this transition");
    }

    private void record(DomainEvent event) {
        eventRecorder.record(event);
    }

    private String nextEventId() {
        return eventRecorder.nextEventId();
    }

    private java.time.Instant now() {
        return eventRecorder.now();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
