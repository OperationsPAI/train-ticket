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
    ) {
        public EntitlementEvidence {
            entitlementId = PostSalesScope.requireText(entitlementId, "entitlementId");
            segmentBookingId = PostSalesScope.requireText(segmentBookingId, "segmentBookingId");
            travelerRef = PostSalesScope.requireText(travelerRef, "travelerRef");
            segmentRef = PostSalesScope.requireText(segmentRef, "segmentRef");
            voidedAt = Objects.requireNonNull(voidedAt, "voidedAt is required");
        }
    }

    public record BookingEvidence(
        String segmentBookingId,
        String entitlementId,
        String travelerRef,
        String segmentRef,
        String capacityHoldId
    ) {
        public BookingEvidence {
            segmentBookingId = PostSalesScope.requireText(segmentBookingId, "segmentBookingId");
            entitlementId = PostSalesScope.requireText(entitlementId, "entitlementId");
            travelerRef = PostSalesScope.requireText(travelerRef, "travelerRef");
            segmentRef = PostSalesScope.requireText(segmentRef, "segmentRef");
            capacityHoldId = PostSalesScope.requireText(capacityHoldId, "capacityHoldId");
        }
    }

    public record CapacityEvidence(
        String capacityHoldId,
        String segmentBookingId,
        boolean absentAfterCancellation
    ) {
        public CapacityEvidence {
            capacityHoldId = PostSalesScope.requireText(capacityHoldId, "capacityHoldId");
            segmentBookingId = PostSalesScope.requireText(segmentBookingId, "segmentBookingId");
        }
    }

    public record RefundEvidence(
        String refundId,
        String paymentIntentId,
        Money amount,
        String status
    ) {
        public RefundEvidence {
            refundId = PostSalesScope.requireText(refundId, "refundId");
            paymentIntentId = PostSalesScope.requireText(paymentIntentId, "paymentIntentId");
            amount = Objects.requireNonNull(amount, "amount is required");
            status = PostSalesScope.requireText(status, "status");
            if (!java.util.Set.of("REQUESTED", "SUBMITTED", "SETTLED").contains(status)) {
                throw new DomainRuleViolation("refund evidence is not an accepted payment state");
            }
        }
    }
}
