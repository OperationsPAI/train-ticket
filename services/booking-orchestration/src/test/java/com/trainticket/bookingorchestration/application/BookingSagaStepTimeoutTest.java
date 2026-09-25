package com.trainticket.bookingorchestration.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trainticket.bookingorchestration.domain.BookingSaga;
import com.trainticket.bookingorchestration.domain.BookingSagaStatus;
import com.trainticket.bookingorchestration.domain.BookingSagaStep;
import com.trainticket.bookingorchestration.domain.BookingStepStatus;
import com.trainticket.bookingorchestration.domain.SegmentBooking;
import com.trainticket.bookingorchestration.domain.SegmentBookingStatus;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.PrefixedIds;
import com.trainticket.platformkit.persistence.OptimisticConcurrencyException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BookingSagaStepTimeoutTest {
    private static final Instant START = Instant.parse("2026-09-24T02:00:00Z");
    private static final String ORDER = "ord-01a0d125-0000-7000-8000-000000000001";
    private static final String TRAVELER = "tvl-01a0d125-0000-7000-8000-000000000002";
    private static final String SEGMENT = "seg-01a0c65a-0000-7000-8000-000000000003";
    private static final String CORRELATION = "corr-01a0d125-0000-7000-8000-000000000004";

    @Test
    void reservationThatNeverConfirmsFailsItsStepAndReleasesTheHold() {
        MutableClock clock = new MutableClock(START);
        RecordingEventPublisher published = new RecordingEventPublisher();
        BookingSagaStepDeadlineRepository deadlines = new InMemoryBookingSagaStepDeadlineRepository();
        BookingSagaRepository sagas = new InMemoryBookingSagaRepository();
        SegmentBookingRepository bookings = new InMemorySegmentBookingRepository();
        BookingOrchestrationService service =
            new BookingOrchestrationService(clock, published, sagas, bookings,
                new InMemoryPaymentIntentSagaReferenceRepository(), new InMemoryConsumedEventRepository(), deadlines);

        String sagaId = startPurchaseSaga(service, clock);
        String bookingId = requestReservation(service, sagaId);
        assertEquals(SegmentBookingStatus.REQUESTED, bookings.findById(bookingId).orElseThrow().booking().status());

        clock.advance(Duration.ofMinutes(6));
        String stepKey = sagaId + ":" + SEGMENT;
        assertEquals(List.of(new BookingSagaStepDeadlineRepository.BookingSagaStepDeadline(sagaId, stepKey,
            START.plus(Duration.ofMinutes(5)))), service.findDueStepDeadlines(10));
        published.clear();

        new BookingSagaTimeoutSweeper(service).sweepTimedOutSteps();

        BookingSaga saga = sagas.findById(sagaId).orElseThrow();
        assertEquals(BookingSagaStatus.FAILED, saga.status());
        assertEquals("SAGA_STEP_TIMED_OUT", saga.terminalReason().orElseThrow());
        assertEquals(BookingStepStatus.FAILED, saga.step(stepKey).status());
        assertEquals("SAGA_STEP_TIMED_OUT", saga.step(stepKey).failureReason().orElseThrow());
        SegmentBooking booking = bookings.findById(bookingId).orElseThrow().booking();
        assertEquals(SegmentBookingStatus.CANCELLED, booking.status());
        assertEquals("SAGA_STEP_TIMED_OUT", booking.cancellationReason().orElseThrow());
        assertEquals(List.of("SegmentBookingCancelled", "BookingSagaFailed"), published.eventTypes());
        assertEquals(List.of(), service.findDueStepDeadlines(10));
    }

    @Test
    void repeatedSweepsFailTheStepOnceAndCompensateOnce() {
        MutableClock clock = new MutableClock(START);
        RecordingEventPublisher published = new RecordingEventPublisher();
        BookingSagaStepDeadlineRepository deadlines = new InMemoryBookingSagaStepDeadlineRepository();
        BookingOrchestrationService service = service(clock, published, deadlines);

        String sagaId = startPurchaseSaga(service, clock);
        String bookingId = requestReservation(service, sagaId);
        clock.advance(Duration.ofMinutes(6));

        assertTrue(service.expireTimedOutStep(sagaId, sagaId + ":" + SEGMENT));
        List<String> afterFirstSweep = published.eventTypes();
        assertFalse(service.expireTimedOutStep(sagaId, sagaId + ":" + SEGMENT));
        new BookingSagaTimeoutSweeper(service).sweepTimedOutSteps();

        assertEquals(afterFirstSweep, published.eventTypes());
        assertEquals(1, count(afterFirstSweep, "SegmentBookingCancelled"));
        assertEquals(1, count(afterFirstSweep, "BookingSagaFailed"));
        assertEquals("CANCELLED", service.getSegmentBooking(bookingId).orElseThrow().status(),
            "the compensation runs once");
    }

    @Test
    void aConfirmationThatOutrunsTheHoldSettlesWhenTheHoldArrives() {
        MutableClock clock = new MutableClock(START);
        RecordingEventPublisher published = new RecordingEventPublisher();
        BookingSagaStepDeadlineRepository deadlines = new InMemoryBookingSagaStepDeadlineRepository();
        BookingSagaRepository sagas = new InMemoryBookingSagaRepository();
        SegmentBookingRepository bookings = new InMemorySegmentBookingRepository();
        BookingOrchestrationService service =
            new BookingOrchestrationService(clock, published, sagas, bookings,
                new InMemoryPaymentIntentSagaReferenceRepository(), new InMemoryConsumedEventRepository(), deadlines);

        String sagaId = startPurchaseSaga(service, clock);
        String bookingId = requestReservation(service, sagaId);
        // The provider confirms first and capacity follows: the reservation is
        // not complete until the hold exists, so the saga waits for it.
        confirmReservation(service, bookingId);
        assertEquals(BookingSagaStatus.RESERVING, sagas.findById(sagaId).orElseThrow().status(),
            "a confirmation without its hold does not settle the reservation step");
        assertEquals("HOLDING", service.getSegmentBooking(bookingId).orElseThrow().status());

        holdCapacity(service, bookingId);
        assertEquals(BookingSagaStatus.AWAITING_PAYMENT, sagas.findById(sagaId).orElseThrow().status());
        assertEquals(List.of(), service.findDueStepDeadlines(10));

        clock.advance(Duration.ofHours(1));
        published.clear();
        assertFalse(service.expireTimedOutStep(sagaId, sagaId + ":" + SEGMENT),
            "a settled step keeps its outcome even when a sweep still holds its old deadline");

        assertEquals(List.of(), published.eventTypes());
        assertEquals(BookingSagaStatus.AWAITING_PAYMENT, sagas.findById(sagaId).orElseThrow().status());
        assertEquals(BookingStepStatus.SUCCEEDED, sagas.findById(sagaId).orElseThrow().step(sagaId + ":" + SEGMENT).status());
        assertEquals(SegmentBookingStatus.CONFIRMED, bookings.findById(bookingId).orElseThrow().booking().status());
    }

    @Test
    void changeReservationTimeoutLeavesTheReplacementWithItsCase() {
        MutableClock clock = new MutableClock(START);
        RecordingEventPublisher published = new RecordingEventPublisher();
        BookingSagaStepDeadlineRepository deadlines = new InMemoryBookingSagaStepDeadlineRepository();
        BookingSagaRepository sagas = new InMemoryBookingSagaRepository();
        SegmentBookingRepository bookings = new InMemorySegmentBookingRepository();
        BookingOrchestrationService service =
            new BookingOrchestrationService(clock, published, sagas, bookings,
                new InMemoryPaymentIntentSagaReferenceRepository(), new InMemoryConsumedEventRepository(), deadlines);
        String caseId = "psc-01a0d125-0000-7000-8000-000000000005";

        var change = service.requestChangeReservation(caseId, ORDER, "ofi-01a0d125-0000-7000-8000-000000000006",
            SEGMENT, TRAVELER, "SECOND_CLASS", "ss-01a0c65a-0000-7000-8000-000000000007", "2026-10-24", CORRELATION);
        String sagaId = change.sagaId();
        clock.advance(Duration.ofMinutes(16));
        published.clear();

        assertTrue(service.expireTimedOutStep(sagaId, sagaId + ":reservations"));

        BookingSaga saga = sagas.findById(sagaId).orElseThrow();
        assertEquals(BookingSagaStatus.FAILED, saga.status());
        assertEquals(BookingStepStatus.FAILED, saga.step(sagaId + ":reservations").status());
        assertEquals(List.of("BookingSagaFailed"), published.eventTypes(),
            "the post sales case owns the replacement reservation and cancels it itself");
        assertEquals("REQUESTED", service.getSegmentBooking(change.segmentBookingId()).orElseThrow().status());
    }

    @Test
    void stepWithoutARecordedWaitStartIsNeverFailed() {
        MutableClock clock = new MutableClock(START);
        RecordingEventPublisher published = new RecordingEventPublisher();
        BookingSagaStepDeadlineRepository deadlines = new InMemoryBookingSagaStepDeadlineRepository();
        BookingSagaRepository sagas = new InMemoryBookingSagaRepository();
        BookingOrchestrationService service =
            new BookingOrchestrationService(clock, published, sagas,
                new InMemorySegmentBookingRepository(), new InMemoryPaymentIntentSagaReferenceRepository(),
                new InMemoryConsumedEventRepository(), deadlines);
        BookingSaga legacy = BookingSaga.start("saga-legacy", ORDER, "1", "purchase", List.of(
            BookingSaga.stepPlan("change-reservations", "saga-legacy:reservations",
                Duration.ofMinutes(15), 3, "release-capacity")), clock);
        legacy.advanceTo(BookingSagaStatus.CONFIRMING);
        sagas.save(legacy, CORRELATION);

        clock.advance(Duration.ofHours(2));

        assertEquals(List.of(), service.findDueStepDeadlines(10));
        assertFalse(service.expireTimedOutStep("saga-legacy", "saga-legacy:reservations"));
        assertEquals(List.of(), published.eventTypes());
        assertEquals(BookingSagaStatus.CONFIRMING, sagas.findById("saga-legacy").orElseThrow().status());
    }

    @Test
    void sweepThatLosesTheOptimisticRacePublishesNothingAndRetriesLater() {
        MutableClock clock = new MutableClock(START);
        RecordingEventPublisher published = new RecordingEventPublisher();
        BookingSagaStepDeadlineRepository deadlines = new InMemoryBookingSagaStepDeadlineRepository();
        RacingBookingSagaRepository sagas = new RacingBookingSagaRepository(clock);
        BookingOrchestrationService service =
            new BookingOrchestrationService(clock, published, sagas, new InMemorySegmentBookingRepository(),
                new InMemoryPaymentIntentSagaReferenceRepository(), new InMemoryConsumedEventRepository(), deadlines);

        String sagaId = startPurchaseSaga(service, clock);
        String bookingId = requestReservation(service, sagaId);
        clock.advance(Duration.ofMinutes(6));
        published.clear();

        sagas.rejectNextSave();
        assertThrows(OptimisticConcurrencyException.class,
            () -> service.expireTimedOutStep(sagaId, sagaId + ":" + SEGMENT));
        assertEquals(List.of(), published.eventTypes(), "a losing sweep publishes nothing");
        assertEquals(BookingSagaStatus.RESERVING, sagas.findById(sagaId).orElseThrow().status());
        assertEquals("REQUESTED", service.getSegmentBooking(bookingId).orElseThrow().status());

        sagas.rejectNextSave();
        new BookingSagaTimeoutSweeper(service).sweepTimedOutSteps();
        assertEquals(List.of(), published.eventTypes(), "the scheduled sweep survives the race");
        assertEquals(1, service.findDueStepDeadlines(10).size(), "the wait stays indexed for the next tick");

        new BookingSagaTimeoutSweeper(service).sweepTimedOutSteps();
        assertEquals(BookingSagaStatus.FAILED, sagas.findById(sagaId).orElseThrow().status());
        assertEquals(List.of("SegmentBookingCancelled", "BookingSagaFailed"), published.eventTypes());
    }

    private static BookingOrchestrationService service(MutableClock clock, RecordingEventPublisher published,
                                                       BookingSagaStepDeadlineRepository deadlines) {
        return new BookingOrchestrationService(clock, published, new InMemoryBookingSagaRepository(),
            new InMemorySegmentBookingRepository(), new InMemoryPaymentIntentSagaReferenceRepository(),
            new InMemoryConsumedEventRepository(), deadlines);
    }

    private static String startPurchaseSaga(BookingOrchestrationService service, MutableClock clock) {
        var result = service.startSaga(new BookingOrchestrationService.StartSagaCommand(
            ORDER, "acc-1", "ofi-1", List.of(TRAVELER), List.of(SEGMENT)), "key-1", CORRELATION);
        service.handleUpstreamEvent(new EventEnvelope(PrefixedIds.newEventId(), "RiskAssessmentCompleted",
            clock.instant(), CORRELATION, PrefixedIds.newCommandId(), "risk-compliance", 1,
            Map.of("sagaId", result.sagaId(), "verdict", "PASS")));
        return result.sagaId();
    }

    private static String requestReservation(BookingOrchestrationService service, String sagaId) {
        String segmentBookingId = "sb-" + PrefixedIds.newEventId();
        var result = service.requestReservation(sagaId,
            new BookingOrchestrationService.RequestReservationCommand(SEGMENT, TRAVELER, segmentBookingId),
            new ReservationFactsResolver.Facts(ORDER, SEGMENT, TRAVELER, "ofi-1", "SECOND_CLASS",
                "ss-01a0c65a-0000-7000-8000-000000000007", "2026-10-24", false),
            CORRELATION);
        return result.segmentBookingId();
    }

    private static void confirmReservation(BookingOrchestrationService service, String segmentBookingId) {
        service.handleUpstreamEvent(new EventEnvelope(PrefixedIds.newEventId(), "ProviderReservationConfirmed",
            START.plus(Duration.ofSeconds(1)), CORRELATION, PrefixedIds.newCommandId(), "provider-integration", 1,
            Map.of("segmentBookingId", segmentBookingId,
                "providerReference", Map.of("providerId", "provider-1", "reservationId", "prv-1",
                    "displayReference", "PRV1"),
                "normalizedEvidence", "confirmed")));
    }

    private static void holdCapacity(BookingOrchestrationService service, String segmentBookingId) {
        service.handleUpstreamEvent(new EventEnvelope(PrefixedIds.newEventId(), "CapacityHeld",
            START.plus(Duration.ofSeconds(2)), CORRELATION, PrefixedIds.newCommandId(), "capacity-availability", 1,
            Map.ofEntries(
                Map.entry("segmentBookingId", segmentBookingId),
                Map.entry("holdId", "hold-1"),
                Map.entry("inventoryPoolId", "pool:seg-001:standard"),
                Map.entry("capacityUnitRef", "su-hold-1"),
                Map.entry("idempotencyKey", "ord-1:seg-1:tvl-1:purchase"),
                Map.entry("expiresAt", START.plus(Duration.ofMinutes(5)).toString()),
                Map.entry("idempotentReplay", false),
                Map.entry("interval", Map.of("fromSeq", 0, "toSeq", 1)))));
    }

    private static int count(List<String> eventTypes, String eventType) {
        return (int) eventTypes.stream().filter(eventType::equals).count();
    }

    private static final class RecordingEventPublisher implements EventPublisher {
        private final List<EventEnvelope> published = new ArrayList<>();

        @Override
        public void publish(EventEnvelope envelope) {
            published.add(envelope);
        }

        List<String> eventTypes() {
            return published.stream().map(EventEnvelope::eventType).toList();
        }

        void clear() {
            published.clear();
        }
    }

    /**
     * Reads snapshots as copies and checks the version on save, the way the Postgres repository
     * does, so a sweep that loses the write race against another writer is exercised. Rejecting a
     * save leaves the stored saga untouched, and the next sweep reads it again.
     */
    private static final class RacingBookingSagaRepository implements BookingSagaRepository {
        private final BookingSagaRepository delegate = new InMemoryBookingSagaRepository();
        private final Clock clock;
        private boolean rejectNextSave;

        RacingBookingSagaRepository(Clock clock) {
            this.clock = clock;
        }

        void rejectNextSave() {
            rejectNextSave = true;
        }

        @Override
        public void lockReference(String reference) {
            delegate.lockReference(reference);
        }

        @Override
        public Optional<String> findCorrelationId(String sagaId) {
            return delegate.findCorrelationId(sagaId);
        }

        @Override
        public Optional<BookingSaga> findById(String sagaId) {
            return delegate.findById(sagaId).map(this::copyOf);
        }

        @Override
        public Optional<BookingSaga> findByJourneyOrderId(String journeyOrderId) {
            return delegate.findByJourneyOrderId(journeyOrderId).map(this::copyOf);
        }

        @Override
        public Optional<BookingSaga> findByCorrelationId(String correlationId) {
            return delegate.findByCorrelationId(correlationId).map(this::copyOf);
        }

        @Override
        public void save(BookingSaga saga, String correlationId) {
            if (rejectNextSave) {
                rejectNextSave = false;
                throw new OptimisticConcurrencyException("snapshot version conflict for " + saga.sagaId());
            }
            delegate.save(saga, correlationId);
        }

        private BookingSaga copyOf(BookingSaga saga) {
            List<BookingSagaStep> steps = saga.steps().stream()
                .map(step -> BookingSagaStep.rehydrate(step.name(), step.idempotencyKey(), step.timeout(),
                    step.retryLimit(), step.compensationAction(), step.status(), step.attemptNumber(),
                    step.startedAt().orElse(null), step.failureReason().orElse(null)))
                .toList();
            return BookingSaga.rehydrate(saga.sagaId(), saga.journeyOrderId(), saga.idempotencyKey(), saga.status(),
                steps, saga.terminalReason().orElse(null), clock).withVersion(saga.version());
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
