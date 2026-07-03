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
    void failedReservationDoesNotProgressToTicketed() {
        SegmentBooking booking = SegmentBooking.requestReservation("booking-1", "order-1", "offer-item-1",
            "rail-segment-1", "traveler-1", "initial", clock);

        booking.failReservation("capacity hold rejected");

        assertEquals(SegmentBookingStatus.FAILED, booking.status());
        assertEquals("capacity hold rejected", booking.failureReason().orElseThrow());
        assertThrows(IllegalStateException.class, () -> booking.markTicketed("entitlement-1"));
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
