package com.trainticket.postsales.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Authoritative downstream facts used to account for an already completed execution. */
public record PostSalesExecutionEvidence(
    String caseId,
    String journeyOrderId,
    List<EntitlementEvidence> entitlements,
    List<BookingEvidence> bookings,
    List<CapacityEvidence> capacity,
    List<RefundEvidence> refunds
) {
    public PostSalesExecutionEvidence {
        caseId = PostSalesScope.requireText(caseId, "caseId");
        journeyOrderId = PostSalesScope.requireText(journeyOrderId, "journeyOrderId");
        entitlements = List.copyOf(Objects.requireNonNull(entitlements, "entitlements are required"));
        bookings = List.copyOf(Objects.requireNonNull(bookings, "bookings are required"));
        capacity = List.copyOf(Objects.requireNonNull(capacity, "capacity evidence is required"));
        refunds = List.copyOf(Objects.requireNonNull(refunds, "refunds are required"));
    }

    public record EntitlementEvidence(
        String entitlementId,
        String segmentBookingId,
        String travelerRef,
        String segmentRef,
        Instant voidedAt
    ) { }

    public record BookingEvidence(
        String segmentBookingId,
        String entitlementId,
        String travelerRef,
        String segmentRef,
        String capacityHoldId
    ) { }

    public record CapacityEvidence(
        String capacityHoldId,
        String segmentBookingId,
        boolean absentAfterCancellation
    ) { }

    public record RefundEvidence(
        String refundId,
        String paymentIntentId,
        Money amount,
        String status
    ) { }
}
