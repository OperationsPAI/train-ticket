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
    private String seatAllocationRef;
    private String classRef;
    private String scheduledServiceRef;
    private String serviceDate;
    private boolean standingAccepted;
    /**
     * A provider confirmation that arrived before the capacity Hold.
     *
     * <p>The two arrive on separate streams, and the reservation is only
     * complete when both are recorded: everything that reads a segment booking
     * reads `CONFIRMED` together with its hold reference, so a confirmation
     * that outruns the hold is a state nobody can act on. It is held here until
     * the hold arrives rather than applied immediately, and it is durable
     * because a restart between the two events would otherwise lose it.
     */
    private ProviderReference pendingProviderReference;
    private String pendingProviderEvidence;
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
        return rehydrate(segmentBookingId, journeyOrderId, offerItemRef, segmentRef, travelerRef, bookingPurpose,
            status, capacityHoldId, providerReference, entitlementId, failureReason, cancellationReason, null, clock);
    }

    public static SegmentBooking rehydrate(String segmentBookingId, String journeyOrderId, String offerItemRef,
                                           String segmentRef, String travelerRef, String bookingPurpose,
                                           SegmentBookingStatus status, String capacityHoldId,
                                           ProviderReference providerReference, String entitlementId,
                                           String failureReason, String cancellationReason,
                                           String seatAllocationRef, Clock clock) {
        SegmentBooking booking = new SegmentBooking(segmentBookingId, journeyOrderId, offerItemRef, segmentRef,
            travelerRef, bookingPurpose, clock);
        booking.status = java.util.Objects.requireNonNull(status, "status is required");
        booking.capacityHoldId = capacityHoldId;
        booking.providerReference = providerReference;
        booking.entitlementId = entitlementId;
        booking.failureReason = failureReason;
        booking.cancellationReason = cancellationReason;
        booking.seatAllocationRef = seatAllocationRef;
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

    public String classRef() { return classRef; }
    public SegmentBooking withClassRef(String classRef) { this.classRef=classRef; return this; }
    public boolean standingAccepted() { return standingAccepted; }
    public SegmentBooking withStandingAcceptance(boolean accepted) {
        this.standingAccepted = accepted;
        return this;
    }
    public String purchaseSourceType() {
        if ("purchase".equals(bookingPurpose)) return "PURCHASE_ORDER";
        if (bookingPurpose.startsWith("change:") && bookingPurpose.length() > "change:".length()) return "CHANGE_CASE";
        return null;
    }
    public String purchaseSourceRef() {
        if ("purchase".equals(bookingPurpose)) return journeyOrderId;
        if (bookingPurpose.startsWith("change:") && bookingPurpose.length() > "change:".length()) {
            return bookingPurpose.substring("change:".length());
        }
        return null;
    }
    public String scheduledServiceRef() { return scheduledServiceRef; }
    public String serviceDate() { return serviceDate; }
    public SegmentBooking withSupplyReference(String service, String date) {
        if (service == null && date == null) return this;
        String resolvedService = requireText(service, "scheduledServiceRef");
        String resolvedDate = java.time.LocalDate.parse(requireText(date, "serviceDate")).toString();
        if (scheduledServiceRef != null && (!scheduledServiceRef.equals(resolvedService) || !serviceDate.equals(resolvedDate))) {
            throw new IllegalStateException("reservation already references another scheduled supply");
        }
        scheduledServiceRef = resolvedService;
        serviceDate = resolvedDate;
        return this;
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

    public Optional<String> seatAllocationRef() {
        return Optional.ofNullable(seatAllocationRef);
    }

    public void assignSeat(String seatAllocationRef) {
        this.seatAllocationRef = requireText(seatAllocationRef, "seatAllocationRef");
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
        completeDeferredProviderConfirmation();
    }

    /**
     * Record the provider's confirmation, and confirm the reservation only when
     * capacity is already held.
     *
     * @return whether the reservation is now confirmed
     */
    public boolean confirmFromProvider(ProviderReservationConfirmed confirmation) {
        if (!segmentBookingId.equals(confirmation.segmentBookingId())) {
            throw new IllegalArgumentException("provider confirmation belongs to a different segment booking");
        }
        if (isTerminalOrCancelling()) {
            registerLateProviderConfirmation(confirmation.providerReference());
            return false;
        }
        if (capacityHoldId == null) {
            // Waiting here rather than confirming without a hold is what makes
            // `CONFIRMED` mean a complete reservation. If the hold never comes,
            // the saga's own reservation deadline is what fails it.
            this.pendingProviderReference = confirmation.providerReference();
            this.pendingProviderEvidence = requireText(confirmation.normalizedEvidence(), "evidence");
            if (status == SegmentBookingStatus.REQUESTED) {
                this.status = SegmentBookingStatus.HOLDING;
            }
            return false;
        }
        confirmReservation(Optional.of(confirmation.providerReference()), confirmation.normalizedEvidence());
        return true;
    }

    private void completeDeferredProviderConfirmation() {
        if (pendingProviderReference == null) {
            return;
        }
        ProviderReference reference = pendingProviderReference;
        String evidence = pendingProviderEvidence;
        pendingProviderReference = null;
        pendingProviderEvidence = null;
        confirmReservation(Optional.of(reference), evidence);
    }

    public Optional<ProviderReference> pendingProviderReference() {
        return Optional.ofNullable(pendingProviderReference);
    }

    public Optional<String> pendingProviderEvidence() {
        return Optional.ofNullable(pendingProviderEvidence);
    }

    /** Restore a confirmation that was waiting for its capacity Hold. */
    public SegmentBooking withPendingProviderConfirmation(ProviderReference reference, String evidence) {
        if (reference == null) {
            this.pendingProviderReference = null;
            this.pendingProviderEvidence = null;
            return this;
        }
        this.pendingProviderReference = reference;
        this.pendingProviderEvidence = evidence;
        return this;
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
        ProviderReference confirmedProvider = providerReference != null ? providerReference : pendingProviderReference;
        if (confirmedProvider != null && capacityHoldId == null) {
            // The provider reserved but the reservation never completed, so it
            // has to be told to cancel -- whether the confirmation was applied
            // before this change or is still waiting for a Hold that will now
            // never arrive.
            record(new ProviderCancellationRequired(nextEventId(), segmentBookingId, now(), confirmedProvider,
                "capacity reservation failed"));
        } else {
            requireStatus(SegmentBookingStatus.REQUESTED, SegmentBookingStatus.HOLDING, SegmentBookingStatus.CANCEL_REQUESTED);
        }
        this.pendingProviderReference = null;
        this.pendingProviderEvidence = null;
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
