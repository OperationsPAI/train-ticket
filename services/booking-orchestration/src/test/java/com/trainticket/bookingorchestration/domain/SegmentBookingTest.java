package com.trainticket.bookingorchestration.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SegmentBookingTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-28T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void movesThroughInternalReservationConfirmationTicketingAndCancellation() {
        SegmentBooking booking = SegmentBooking.requestReservation("booking-1", "order-1", "offer-item-1",
            "rail-segment-1", "traveler-1", "initial", clock);

        assertEquals(SegmentBookingStatus.REQUESTED, booking.status());
        assertEquals("order-1:rail-segment-1:traveler-1:initial", booking.idempotencyKey());
        assertInstanceOf(SegmentBookingEvent.SegmentReservationRequested.class, booking.peekEvents().getFirst());

        booking.markCapacityHolding("hold-1");
        booking.confirmInternal("capacity hold confirmed by policy");
        booking.markTicketed("entitlement-1");
        booking.requestCancellation("post sales approved");
        booking.markCancelled("provider and capacity cancellation complete");

        assertEquals(SegmentBookingStatus.CANCELLED, booking.status());
        assertEquals("hold-1", booking.capacityHoldId().orElseThrow());
        assertEquals("entitlement-1", booking.entitlementId().orElseThrow());
    }

    @Test
    void acceptsOnlyNormalizedProviderConfirmationAndDoesNotExposeRawSupplierCodes() {
        SegmentBooking booking = SegmentBooking.requestReservation("booking-1", "order-1", "offer-item-1",
            "rail-segment-1", "traveler-1", "initial", clock);
        booking.pullEvents();

        ProviderReference providerReference = new ProviderReference("provider-acl", "reservation-42", "PNR-42");
        ProviderReservationConfirmed confirmation = new ProviderReservationConfirmed("booking-1", providerReference,
            "confirmed", Map.of("seatClass", "SECOND"));

        // Capacity is held before a reservation can be confirmed: the two
        // events arrive on separate streams and either can be first.
        booking.markCapacityHolding("hold-1");
        booking.confirmFromProvider(confirmation);

        assertEquals(SegmentBookingStatus.CONFIRMED, booking.status());
        assertEquals(providerReference, booking.providerReference().orElseThrow());
        assertTrue(booking.peekEvents().stream().anyMatch(SegmentBookingEvent.ProviderReferenceAttached.class::isInstance));
        assertTrue(booking.peekEvents().stream().anyMatch(SegmentBookingEvent.SegmentReservationConfirmed.class::isInstance));
        assertFalse(confirmation.normalizedAttributes().containsKey("rawSupplierStatusCode"));

        ProviderReservationConfirmed duplicate = new ProviderReservationConfirmed("booking-1", providerReference,
            "confirmed", Map.of());
        booking.confirmFromProvider(duplicate);
        long confirmedEvents = booking.peekEvents().stream()
            .filter(SegmentBookingEvent.SegmentReservationConfirmed.class::isInstance)
            .count();
        assertEquals(1, confirmedEvents);

        ProviderReservationConfirmed conflicting = new ProviderReservationConfirmed("booking-1",
            new ProviderReference("provider-acl", "reservation-99", "PNR-99"), "confirmed", Map.of());
        assertThrows(IllegalStateException.class, () -> booking.confirmFromProvider(conflicting));
    }

    @Test
    void providerTimeoutKeepsReservationPendingForStatusQueryRecovery() {
        SegmentBooking booking = SegmentBooking.requestReservation("booking-1", "order-1", "offer-item-1",
            "rail-segment-1", "traveler-1", "initial", clock);
        booking.pullEvents();

        booking.markProviderReservationTimeout("provider reserve timeout");

        assertEquals(SegmentBookingStatus.REQUESTED, booking.status());
        assertInstanceOf(SegmentBookingEvent.ProviderReservationTimedOut.class, booking.peekEvents().getFirst());
    }

    @Test
    void exposesImmutablePurchaseAndChangeSources() {
        SegmentBooking purchase = SegmentBooking.requestReservation("booking-purchase", "order-1", "offer-item-1",
            "rail-segment-1", "traveler-1", "purchase", clock).withStandingAcceptance(true);
        SegmentBooking change = SegmentBooking.requestReservation("booking-change", "order-1", "offer-item-2",
            "rail-segment-1", "traveler-1", "change:case-42", clock);

        assertEquals("PURCHASE_ORDER", purchase.purchaseSourceType());
        assertEquals("order-1", purchase.purchaseSourceRef());
        assertTrue(purchase.standingAccepted());
        assertEquals("CHANGE_CASE", change.purchaseSourceType());
        assertEquals("case-42", change.purchaseSourceRef());
        assertFalse(change.standingAccepted());
    }

    @Test
    void failedReservationDoesNotProgressToTicketed() {
        SegmentBooking booking = SegmentBooking.requestReservation("booking-1", "order-1", "offer-item-1",
            "rail-segment-1", "traveler-1", "initial", clock);

        booking.failReservation("capacity hold rejected");

        assertEquals(SegmentBookingStatus.FAILED, booking.status());
        assertEquals("capacity hold rejected", booking.failureReason().orElseThrow());
        assertThrows(IllegalStateException.class, () -> booking.markTicketed("entitlement-1"));
    }

    @Test
    void capacityFailureAfterProviderConfirmationFailsTheBookingAndCancelsProvider() {
        SegmentBooking booking = SegmentBooking.requestReservation("booking-capacity", "order-capacity", "offer-item",
            "rail-segment", "traveler", "initial", clock);
        ProviderReference reference = new ProviderReference("provider", "reservation", "PNR");
        booking.confirmFromProvider(new ProviderReservationConfirmed(booking.segmentBookingId(), reference, "confirmed", Map.of()));
        booking.pullEvents();

        booking.failReservation("NO_AVAILABLE_CAPACITY");

        assertEquals(SegmentBookingStatus.FAILED, booking.status());
        assertEquals("NO_AVAILABLE_CAPACITY", booking.failureReason().orElseThrow());
        assertTrue(booking.peekEvents().stream().anyMatch(SegmentBookingEvent.ProviderCancellationRequired.class::isInstance));
        assertTrue(booking.peekEvents().stream().anyMatch(SegmentBookingEvent.SegmentReservationFailed.class::isInstance));
        assertThrows(IllegalStateException.class, () -> booking.markTicketed("entitlement"));
    }

    @Test
    void aProviderConfirmationThatOutrunsTheHoldWaitsForIt() {
        // The two events arrive on separate streams, and a reservation that says
        // CONFIRMED without a hold cannot be acted on by anything that reads it:
        // the load generator boards on that pair. Measured on the resident
        // cluster, 2586 of 2627 confirmed reservations had no hold reference
        // because the client gave up before capacity caught up.
        SegmentBooking booking = SegmentBooking.requestReservation("booking-race", "order-race", "offer-item",
            "rail-segment", "traveler", "purchase", clock);
        ProviderReference reference = new ProviderReference("provider", "reservation-race", "PNR");
        booking.pullEvents();

        assertFalse(booking.confirmFromProvider(
            new ProviderReservationConfirmed(booking.segmentBookingId(), reference, "confirmed", Map.of())));

        assertEquals(SegmentBookingStatus.HOLDING, booking.status());
        assertTrue(booking.capacityHoldId().isEmpty());
        assertEquals(reference, booking.pendingProviderReference().orElseThrow());
        assertTrue(booking.peekEvents().stream()
            .noneMatch(SegmentBookingEvent.SegmentReservationConfirmed.class::isInstance));

        booking.markCapacityHolding("hold-race");

        assertEquals(SegmentBookingStatus.CONFIRMED, booking.status());
        assertEquals("hold-race", booking.capacityHoldId().orElseThrow());
        assertEquals(reference, booking.providerReference().orElseThrow());
        assertTrue(booking.pendingProviderReference().isEmpty());
        assertTrue(booking.peekEvents().stream()
            .anyMatch(SegmentBookingEvent.SegmentReservationConfirmed.class::isInstance));
    }

    @Test
    void aDeferredConfirmationStillCancelsTheProviderWhenCapacityFails() {
        SegmentBooking booking = SegmentBooking.requestReservation("booking-race", "order-race", "offer-item",
            "rail-segment", "traveler", "purchase", clock);
        ProviderReference reference = new ProviderReference("provider", "reservation-race", "PNR");
        booking.confirmFromProvider(
            new ProviderReservationConfirmed(booking.segmentBookingId(), reference, "confirmed", Map.of()));
        booking.pullEvents();

        booking.failReservation("NO_AVAILABLE_CAPACITY");

        assertEquals(SegmentBookingStatus.FAILED, booking.status());
        assertTrue(booking.peekEvents().stream().anyMatch(
            SegmentBookingEvent.ProviderCancellationRequired.class::isInstance));
        assertTrue(booking.pendingProviderReference().isEmpty());
    }

    @Test
    void lateProviderConfirmationAfterCancellationEmitsCancellationHookWithoutReopeningBooking() {
        SegmentBooking booking = SegmentBooking.requestReservation("booking-1", "order-1", "offer-item-1",
            "rail-segment-1", "traveler-1", "initial", clock);
        booking.requestCancellation("payment expired");
        booking.markCancelled("capacity released");
        booking.pullEvents();

        ProviderReference providerReference = new ProviderReference("provider-acl", "reservation-42", "PNR-42");
        booking.confirmFromProvider(new ProviderReservationConfirmed("booking-1", providerReference, "confirmed",
            Map.of()));

        assertEquals(SegmentBookingStatus.CANCELLED, booking.status());
        assertEquals(providerReference, booking.providerReference().orElseThrow());
        assertTrue(booking.peekEvents().stream()
            .anyMatch(SegmentBookingEvent.ProviderConfirmationReceivedAfterCancellation.class::isInstance));
        assertTrue(booking.peekEvents().stream()
            .anyMatch(SegmentBookingEvent.ProviderCancellationRequired.class::isInstance));
    }
}
