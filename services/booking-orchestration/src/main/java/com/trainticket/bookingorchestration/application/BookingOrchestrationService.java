package com.trainticket.bookingorchestration.application;

import org.springframework.beans.factory.annotation.Autowired;
import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.PrefixedIds;
import com.trainticket.bookingorchestration.domain.BookingEvent;
import com.trainticket.bookingorchestration.domain.BookingSaga;
import com.trainticket.bookingorchestration.domain.BookingSagaStatus;
import com.trainticket.bookingorchestration.domain.BookingSagaStep;
import com.trainticket.bookingorchestration.domain.DomainEvent;
import com.trainticket.bookingorchestration.domain.ProviderReference;
import com.trainticket.bookingorchestration.domain.ProviderReservationConfirmed;
import com.trainticket.bookingorchestration.domain.SegmentBooking;
import com.trainticket.bookingorchestration.domain.SegmentBookingEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

@Service
public class BookingOrchestrationService {

    private static final String PRODUCER = "booking-orchestration";
    private static final Logger LOGGER = LoggerFactory.getLogger(BookingOrchestrationService.class);

    private final Clock clock;
    private final EventPublisher eventPublisher;
    private final BookingSagaRepository sagas;
    private final SegmentBookingRepository segmentBookings;
    private final PaymentIntentSagaReferenceRepository paymentIntentRefs;
    private final ConsumedEventRepository consumedEvents;


    public BookingOrchestrationService(Clock clock, EventPublisher eventPublisher) {
        this(clock, eventPublisher, new InMemoryBookingSagaRepository(), new InMemorySegmentBookingRepository(),
            new InMemoryPaymentIntentSagaReferenceRepository(), new InMemoryConsumedEventRepository());
    }

    @Autowired
    public BookingOrchestrationService(Clock clock, EventPublisher eventPublisher, BookingSagaRepository sagas,
                                       SegmentBookingRepository segmentBookings,
                                       PaymentIntentSagaReferenceRepository paymentIntentRefs,
                                       ConsumedEventRepository consumedEvents) {
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher is required");
        this.sagas = Objects.requireNonNull(sagas, "sagas repository is required");
        this.segmentBookings = Objects.requireNonNull(segmentBookings, "segmentBookings repository is required");
        this.paymentIntentRefs = Objects.requireNonNull(paymentIntentRefs, "paymentIntentRefs repository is required");
        this.consumedEvents = Objects.requireNonNull(consumedEvents, "consumedEvents repository is required");
    }

    public StartSagaResult startSaga(StartSagaCommand command, String idempotencyKey, String correlationId) {
        String sagaId = "saga-" + com.trainticket.platformkit.idempotency.UuidV7.generate();
        BookingSaga saga = startSagaAggregate(sagaId, command.journeyOrderId(), command.segmentRefs());
        sagas.save(saga, correlationId);
        publishEvents(saga.pullEvents(), correlationId, "cmd-" + UUID.randomUUID());

        StartSagaResult result = new StartSagaResult(sagaId, command.journeyOrderId(), mapSagaStatus(saga.status()), clock.instant());
        return result;
    }

    public Optional<SagaDetail> getSaga(String sagaId) {
        return sagas.findById(sagaId).map(this::buildSagaDetail);
    }

    public RequestReservationResult requestReservation(String sagaId, RequestReservationCommand command,
                                                       String idempotencyKey, String correlationId) {
        BookingSaga saga = sagas.findById(sagaId)
            .orElseThrow(() -> new NotFoundException("Booking saga not found: " + sagaId));

        SegmentBooking booking = SegmentBooking.requestReservation(
            command.segmentBookingId(), saga.journeyOrderId(), command.segmentRef(), command.segmentRef(),
            command.travelerRef(), "purchase", clock);
        segmentBookings.save(booking, sagaId);
        publishEvents(booking.pullEvents(), correlationId, "cmd-" + UUID.randomUUID());

        RequestReservationResult result = new RequestReservationResult(command.segmentBookingId(), "REQUESTED");
        return result;
    }

    public MarkTicketedResult markTicketed(String sagaId, MarkTicketedCommand command,
                                           String idempotencyKey, String correlationId) {
        sagas.findById(sagaId).orElseThrow(() -> new NotFoundException("Booking saga not found: " + sagaId));

        SegmentBookingRepository.SegmentBookingRecord bookingRecord = segmentBookings.findById(command.segmentBookingId())
            .filter(record -> sagaId.equals(record.sagaId()))
            .orElseThrow(() -> new NotFoundException("Segment booking not found: " + command.segmentBookingId()));
        SegmentBooking booking = bookingRecord.booking();
        try {
            booking.markTicketed(command.entitlementId());
        } catch (IllegalStateException ex) {
            throw new PreconditionFailedException(ex.getMessage(), ex);
        }
        segmentBookings.save(booking, sagaId);
        publishEvents(booking.pullEvents(), correlationId, "cmd-" + UUID.randomUUID());

        MarkTicketedResult result = new MarkTicketedResult(command.segmentBookingId(), "TICKETED");
        return result;
    }

    @Transactional
    public HandlerResult handleUpstreamEvent(EventEnvelope envelope) {
        if (envelope == null || envelope.eventId() == null || envelope.eventId().isBlank()) {
            return new HandlerResult.FatalError("missing event envelope or eventId");
        }
        if (!consumedEvents.recordIfNew(envelope.eventId(), envelope.producer())) {
            return new HandlerResult.Success();
        }
        try {
            dispatchUpstreamEvent(envelope);
            return new HandlerResult.Success();
        } catch (IllegalArgumentException | IllegalStateException | NotFoundException | PreconditionFailedException ex) {
            rollbackCurrentTransactionIfActive();
            return new HandlerResult.FatalError(ex.getMessage());
        } catch (RuntimeException ex) {
            rollbackCurrentTransactionIfActive();
            return new HandlerResult.TransientError(ex.getMessage());
        }
    }

    private static void rollbackCurrentTransactionIfActive() {
        try {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        } catch (RuntimeException ignored) {
            // Unit tests may run without a Spring-managed transaction.
        }
    }

    private void dispatchUpstreamEvent(EventEnvelope envelope) {
        Map<String, Object> payload = payloadMap(envelope.payload());
        switch (envelope.eventType()) {
            case "JourneyOrderCreated" -> handleJourneyOrderCreated(payload, envelope.correlationId(), envelope.eventId());
            case "CapacityHeld", "CapacityHoldConfirmed" -> handleCapacityHeld(envelope.eventType(), payload, envelope.correlationId(), envelope.eventId());
            case "CapacityReleased", "CapacityHoldExpired" -> handleCapacityReleased(envelope.eventType(), payload, envelope.correlationId(), envelope.eventId());
            case "CapacityHoldFailed" -> handleCapacityHoldFailed(payload, envelope.correlationId(), envelope.eventId());
            case "PaymentIntentCreated" -> handlePaymentIntentCreated(payload);
            case "PaymentCaptured" -> handlePaymentCaptured(payload, envelope.correlationId(), envelope.eventId());
            case "PaymentIntentFailed", "PaymentFailed", "PaymentExpired", "PaymentIntentExpired" -> handlePaymentFailure(payload, envelope.correlationId(), envelope.eventId());
            case "ProviderReservationConfirmed" -> handleProviderReservationConfirmed(payload, envelope.correlationId(), envelope.eventId());
            case "ProviderReservationFailed", "ProviderReservationTimedOut" -> handleProviderReservationFailed(payload, envelope.correlationId(), envelope.eventId());
            case "EntitlementIssued" -> handleEntitlementIssued(payload, envelope.correlationId(), envelope.eventId());
            case "EntitlementIssueFailed" -> handleEntitlementIssueFailed(payload, envelope.correlationId(), envelope.eventId());
            case "EntitlementVoided" -> handleEntitlementVoided(payload, envelope.correlationId(), envelope.eventId());
            default -> {
                // Streams contain event types that do not affect this saga.
            }
        }
    }

    private void handleJourneyOrderCreated(Map<String, Object> payload, String correlationId, String causationId) {
        String journeyOrderId = firstText(payload, "journeyOrderId", "orderId");
        if (journeyOrderId == null) {
            throw new IllegalArgumentException("JourneyOrderCreated payload requires orderId");
        }
        if (sagas.findByJourneyOrderId(journeyOrderId).isPresent()) {
            return;
        }
        List<String> segmentRefs = listOfText(payload.get("segmentRefs"));
        if (segmentRefs.isEmpty() && payload.get("segments") instanceof List<?> segments) {
            for (Object segment : segments) {
                if (segment instanceof Map<?, ?> map) {
                    String segmentRef = text(map.get("segmentRef"));
                    if (segmentRef != null) {
                        segmentRefs.add(segmentRef);
                    }
                }
            }
        }
        if (segmentRefs.isEmpty()) {
            throw new IllegalArgumentException("JourneyOrderCreated payload requires segmentRefs or segments");
        }
        String sagaId = "saga-" + com.trainticket.platformkit.idempotency.UuidV7.generate();
        BookingSaga saga = startSagaAggregate(sagaId, journeyOrderId, segmentRefs);
        sagas.save(saga, correlationId);
        publishEvents(saga.pullEvents(), correlationId, causationId);
    }

    private void handleCapacityHeld(String eventType, Map<String, Object> payload, String correlationId, String causationId) {
        validateCapacityHeldContract(eventType, payload);
        String holdId = firstText(payload, "holdId", "capacityHoldId");
        String idempotencyKey = text(payload.get("idempotencyKey"));
        SegmentBooking booking = null;
        if (idempotencyKey != null) {
            booking = segmentBookings.findByIdempotencyKey(idempotencyKey)
                .map(SegmentBookingRepository.SegmentBookingRecord::booking)
                .orElse(null);
        }
        if (booking == null) {
            booking = byHoldId(holdId);
        }
        if (booking == null) {
            booking = bySegmentBookingId(payload);
        }
        if (booking == null) {
            warnCapacityAckSkip(eventType, "unknown segment booking reference");
            return;
        }
        booking.markCapacityHolding(holdId);
        segmentBookings.save(booking, sagaIdForSegmentBooking(booking.segmentBookingId()).orElseThrow());
        publishEvents(booking.pullEvents(), correlationId, causationId);
        markSagaStepSucceeded(eventType, booking.segmentBookingId(), correlationId, causationId);
    }

    private void handleCapacityHoldFailed(Map<String, Object> payload, String correlationId, String causationId) {
        String idempotencyKey = text(payload.get("idempotencyKey"));
        SegmentBooking booking = null;
        if (idempotencyKey != null) {
            booking = segmentBookings.findByIdempotencyKey(idempotencyKey)
                .map(SegmentBookingRepository.SegmentBookingRecord::booking)
                .orElse(null);
        }
        if (booking == null) {
            booking = bySegmentBookingId(payload);
        }
        if (booking == null) {
            // Hold failures for other requesters are not this saga's concern.
            return;
        }
        String reason = firstText(payload, "reason", "failureMessage", "failureCode");
        if (booking.status().name().equals("FAILED") || booking.status().name().equals("CANCELLED")) {
            warnCapacityAckSkip("CapacityHoldFailed", "segment booking already terminal");
            return;
        }
        try {
            booking.failReservation(reason == null ? "capacity hold failed" : reason);
        } catch (IllegalStateException ex) {
            // Redelivered or late hold failure racing a booking that already
            // advanced; the event is conformant, so skip instead of poisoning.
            warnCapacityAckSkip("CapacityHoldFailed", "segment booking state already advanced");
            return;
        }
        String sagaId = sagaIdForSegmentBooking(booking.segmentBookingId()).orElse(null);
        if (sagaId != null) {
            segmentBookings.save(booking, sagaId);
        }
        publishEvents(booking.pullEvents(), correlationId, causationId);
        BookingSaga saga = sagaId == null ? null : sagas.findById(sagaId).orElse(null);
        if (saga != null && saga.status() != BookingSagaStatus.FAILED && saga.status() != BookingSagaStatus.COMPLETED) {
            saga.fail(reason == null ? "capacity hold failed" : reason);
            sagas.save(saga, correlationId);
            publishEvents(saga.pullEvents(), correlationId, causationId);
        }
    }

    private void handleCapacityReleased(String eventType, Map<String, Object> payload, String correlationId, String causationId) {
        validateCapacityReleasedContract(eventType, payload);
        String holdId = firstText(payload, "holdId", "capacityHoldId");
        SegmentBooking booking = byHoldId(holdId);
        if (booking == null) {
            booking = bySegmentBookingId(payload);
        }
        if (booking == null) {
            warnCapacityAckSkip(eventType, "unknown segment booking reference");
            return;
        }
        if (!canApplyCapacityRelease(booking)) {
            warnCapacityAckSkip(eventType, "segment booking already processed or status advanced");
            return;
        }
        String reason = firstText(payload, "releaseReason", "reason");
        booking.markCancelled(reason == null ? "capacity hold released" : reason);
        segmentBookings.save(booking, sagaIdForSegmentBooking(booking.segmentBookingId()).orElseThrow());
        publishEvents(booking.pullEvents(), correlationId, causationId);
    }

    private void handlePaymentIntentCreated(Map<String, Object> payload) {
        String paymentIntentId = text(payload.get("paymentIntentId"));
        String businessRef = text(payload.get("businessRef"));
        if (paymentIntentId == null || businessRef == null) {
            throw new IllegalArgumentException("PaymentIntentCreated payload requires paymentIntentId and businessRef");
        }
        mapPaymentIntent(paymentIntentId, businessRef);
    }

    private void handlePaymentCaptured(Map<String, Object> payload, String correlationId, String causationId) {
        String paymentIntentId = text(payload.get("paymentIntentId"));
        if (paymentIntentId == null) {
            throw new IllegalArgumentException("PaymentCaptured payload requires paymentIntentId");
        }
        String sagaId = sagaIdForPaymentIntent(paymentIntentId, payload);
        BookingSaga saga = sagaId == null ? null : sagas.findById(sagaId).orElse(null);
        if (saga != null) {
            safeAdvance(saga, BookingSagaStatus.TICKETING);
            sagas.save(saga, correlationId);
            publishEvents(saga.pullEvents(), correlationId, causationId);
        }
    }

    private void handlePaymentFailure(Map<String, Object> payload, String correlationId, String causationId) {
        String paymentIntentId = text(payload.get("paymentIntentId"));
        if (paymentIntentId == null) {
            throw new IllegalArgumentException("payment failure payload requires paymentIntentId");
        }
        String sagaId = sagaIdForPaymentIntent(paymentIntentId, payload);
        BookingSaga saga = sagaId == null ? null : sagas.findById(sagaId).orElse(null);
        if (saga != null && saga.status() != BookingSagaStatus.FAILED && saga.status() != BookingSagaStatus.COMPLETED) {
            String reason = firstText(payload, "reason", "reasonCode");
            saga.fail(reason == null ? "payment failed" : reason);
            sagas.save(saga, correlationId);
            publishEvents(saga.pullEvents(), correlationId, causationId);
        }
    }

    private void handleProviderReservationConfirmed(Map<String, Object> payload, String correlationId, String causationId) {
        SegmentBooking booking = bySegmentBookingId(payload);
        if (booking == null) {
            throw new IllegalArgumentException("ProviderReservationConfirmed payload references an unknown segment booking");
        }
        ProviderReference reference = providerReference(payload.get("providerReference"));
        String evidence = firstText(payload, "normalizedEvidence", "evidence");
        booking.confirmFromProvider(new ProviderReservationConfirmed(
            booking.segmentBookingId(), reference, evidence == null ? "provider-confirmed" : evidence, Map.of()));
        segmentBookings.save(booking, sagaIdForSegmentBooking(booking.segmentBookingId()).orElseThrow());
        publishEvents(booking.pullEvents(), correlationId, causationId);
        markSagaStepSucceeded("ProviderReservationConfirmed", booking.segmentBookingId(), correlationId, causationId);
    }

    private void handleProviderReservationFailed(Map<String, Object> payload, String correlationId, String causationId) {
        SegmentBooking booking = bySegmentBookingId(payload);
        if (booking == null) {
            throw new IllegalArgumentException("provider failure payload references an unknown segment booking");
        }
        String reason = firstText(payload, "errorMessage", "reason", "errorType");
        booking.failReservation(reason == null ? "provider reservation failed" : reason);
        segmentBookings.save(booking, sagaIdForSegmentBooking(booking.segmentBookingId()).orElseThrow());
        publishEvents(booking.pullEvents(), correlationId, causationId);
    }

    private void handleEntitlementIssued(Map<String, Object> payload, String correlationId, String causationId) {
        String segmentBookingId = text(payload.get("segmentBookingId"));
        String entitlementId = text(payload.get("entitlementId"));
        if (segmentBookingId == null || entitlementId == null) {
            throw new IllegalArgumentException("EntitlementIssued payload requires segmentBookingId and entitlementId");
        }
        String sagaId = sagaIdForSegmentBooking(segmentBookingId).orElse(null);
        if (sagaId == null) {
            // Post-sales CHANGE issues replacement entitlements against segment
            // bookings this saga never orchestrated. The event is conformant;
            // an unknown local ref is an ack-skip, not a poison pill.
            LOGGER.warn("Ack-skipping EntitlementIssued from entitlement-ticketing: unknown segment booking {}", segmentBookingId);
            return;
        }
        markTicketed(sagaId, new MarkTicketedCommand(segmentBookingId, entitlementId),
            "event:" + causationId + ":" + segmentBookingId, correlationId);
        BookingSaga saga = sagas.findById(sagaId).orElse(null);
        if (saga != null && saga.steps().stream().allMatch(step -> step.status().name().equals("SUCCEEDED"))) {
            saga.complete();
            sagas.save(saga, correlationId);
            publishEvents(saga.pullEvents(), correlationId, causationId);
        }
    }

    private void handleEntitlementIssueFailed(Map<String, Object> payload, String correlationId, String causationId) {
        BookingSaga saga = sagaByCorrelationId(correlationId);
        if (saga == null) {
            LOGGER.info("Ignoring EntitlementIssueFailed with unknown correlationId");
            return;
        }
        String reason = firstText(payload, "failureMessage", "failureCode", "reason");
        String failureReason = reason == null ? "entitlement processing failed" : reason;
        for (SegmentBooking booking : segmentBookingsForSaga(saga.sagaId())) {
            failSegmentBookingForEntitlement(booking, failureReason, correlationId, causationId);
        }
        if (saga.status() != BookingSagaStatus.FAILED && saga.status() != BookingSagaStatus.COMPLETED) {
            saga.fail(failureReason);
            sagas.save(saga, correlationId);
            publishEvents(saga.pullEvents(), correlationId, causationId);
        }
    }

    private void handleEntitlementVoided(Map<String, Object> payload, String correlationId, String causationId) {
        SegmentBooking booking = bySegmentBookingId(payload);
        if (booking == null) {
            return;
        }
        String reason = firstText(payload, "reason", "failureMessage", "failureCode");
        String cancellationReason = reason == null ? "entitlement voided" : reason;
        if (booking.status().name().equals("FAILED") || booking.status().name().equals("CANCELLED")) {
            LOGGER.warn("Ack-skipping EntitlementVoided from entitlement-ticketing: segment booking already terminal");
            return;
        }
        String sagaId = sagaIdForSegmentBooking(booking.segmentBookingId()).orElse(null);
        if (sagaId == null) {
            LOGGER.warn("Ack-skipping EntitlementVoided from entitlement-ticketing: unknown saga for segment booking");
            return;
        }
        // A voided entitlement after ticketing is a cancellation (refund flow):
        // emit SegmentBookingCancelled so capacity releases the hold.
        try {
            booking.requestCancellation(cancellationReason);
            booking.markCancelled(cancellationReason);
        } catch (IllegalStateException ex) {
            LOGGER.warn("Ack-skipping EntitlementVoided from entitlement-ticketing: booking state already advanced");
            return;
        }
        segmentBookings.save(booking, sagaId);
        publishEvents(booking.pullEvents(), correlationId, causationId);
    }

    private void failSegmentBookingForEntitlement(SegmentBooking booking, String reason, String correlationId,
                                                  String causationId) {
        if (booking.status().name().equals("FAILED") || booking.status().name().equals("CANCELLED")) {
            return;
        }
        try {
            booking.failReservation(reason);
            segmentBookings.save(booking, sagaIdForSegmentBooking(booking.segmentBookingId()).orElseThrow());
            publishEvents(booking.pullEvents(), correlationId, causationId);
        } catch (IllegalStateException ex) {
            booking.requestCancellation(reason);
            segmentBookings.save(booking, sagaIdForSegmentBooking(booking.segmentBookingId()).orElseThrow());
            publishEvents(booking.pullEvents(), correlationId, causationId);
        }
    }

    private BookingSaga startSagaAggregate(String sagaId, String journeyOrderId, List<String> segmentRefs) {
        List<BookingSagaStep> plan = new ArrayList<>();
        for (String segmentRef : segmentRefs) {
            plan.add(BookingSaga.stepPlan("reserve-" + segmentRef, sagaId + ":" + segmentRef,
                Duration.ofMinutes(5), 3, "release-capacity"));
        }
        return BookingSaga.start(sagaId, journeyOrderId, "1", "purchase", plan, clock);
    }

    private void markSagaStepSucceeded(String eventType, String segmentBookingId, String correlationId, String causationId) {
        SegmentBookingRepository.SegmentBookingRecord record = segmentBookings.findById(segmentBookingId).orElse(null);
        SegmentBooking booking = record == null ? null : record.booking();
        String sagaId = record == null ? null : record.sagaId();
        BookingSaga saga = sagaId == null ? null : sagas.findById(sagaId).orElse(null);
        if (booking == null || saga == null) {
            return;
        }
        if (saga.status() != BookingSagaStatus.RESERVING) {
            if (eventType.startsWith("Capacity")) {
                warnCapacityAckSkip(eventType, "saga already processed capacity step");
            }
            return;
        }
        String stepKey = sagaId + ":" + booking.segmentRef();
        try {
            saga.recordStepSucceeded(stepKey);
            if (saga.steps().stream().allMatch(step -> step.status().name().equals("SUCCEEDED"))) {
                saga.advanceTo(BookingSagaStatus.AWAITING_PAYMENT);
            }
            sagas.save(saga, correlationId);
            publishEvents(saga.pullEvents(), correlationId, causationId);
        } catch (IllegalArgumentException ignored) {
            // The saga plan may have been started from upstream order data with a different step layout.
        }
    }

    private void safeAdvance(BookingSaga saga, BookingSagaStatus nextStatus) {
        if (saga.status() == nextStatus || saga.status() == BookingSagaStatus.COMPLETED || saga.status() == BookingSagaStatus.FAILED) {
            return;
        }
        saga.advanceTo(nextStatus);
    }

    private void publishEvents(List<DomainEvent> events, String correlationId, String causationId) {
        for (DomainEvent event : events) {
            Optional<ContractEvent> contractEvent = toContractEvent(event);
            if (contractEvent.isEmpty()) {
                continue;
            }
            eventPublisher.publish(new EventEnvelope(
                PrefixedIds.newEventId(),
                contractEvent.get().eventType(),
                clock.instant(),
                PrefixedIds.isCorrelationId(correlationId) ? correlationId : PrefixedIds.newCorrelationId(),
                PrefixedIds.isCausationId(causationId) ? causationId : PrefixedIds.newCommandId(),
                PRODUCER,
                1,
                contractEvent.get().payload()));
        }
    }

    Optional<ContractEvent> toContractEvent(DomainEvent event) {
        return switch (event) {
            case BookingEvent.BookingSagaStarted started -> Optional.of(new ContractEvent(
                "BookingSagaStarted",
                new BookingSagaStartedPayload(started.aggregateId(), started.journeyOrderId(), started.occurredAt())));
            case SegmentBookingEvent.SegmentReservationRequested requested -> Optional.of(new ContractEvent(
                "SegmentReservationRequested",
                new SegmentReservationRequestedPayload(requested.aggregateId(), requested.journeyOrderId(),
                    requested.segmentRef(), requested.travelerRef(), requested.idempotencyKey())));
            case SegmentBookingEvent.SegmentCapacityHolding holding -> Optional.of(new ContractEvent(
                "SegmentCapacityHolding",
                new SegmentCapacityHoldingPayload(holding.aggregateId(), holding.capacityHoldId())));
            case SegmentBookingEvent.SegmentReservationConfirmed confirmed -> Optional.of(new ContractEvent(
                "SegmentReservationConfirmed",
                new SegmentReservationConfirmedPayload(confirmed.aggregateId(),
                    confirmed.providerReference().orElse(null), confirmed.evidence())));
            case SegmentBookingEvent.SegmentReservationFailed failed -> Optional.of(new ContractEvent(
                "SegmentReservationFailed",
                new SegmentReservationFailedPayload(failed.aggregateId(), failed.reason())));
            case SegmentBookingEvent.SegmentBookingCancelled cancelled -> Optional.of(new ContractEvent(
                "SegmentBookingCancelled",
                new SegmentBookingCancelledPayload(cancelled.aggregateId(), cancelled.reason(), holdIdForSegmentBooking(cancelled.aggregateId()))));
            case SegmentBookingEvent.SegmentTicketed ticketed -> Optional.of(new ContractEvent(
                "SegmentTicketed",
                new SegmentTicketedPayload(ticketed.aggregateId(), ticketed.entitlementId())));
            case BookingEvent.BookingSagaCompleted completed -> Optional.of(new ContractEvent(
                "BookingSagaCompleted",
                new BookingSagaCompletedPayload(completed.aggregateId(), journeyOrderIdForSaga(completed.aggregateId()))));
            case BookingEvent.BookingSagaFailed failed -> Optional.of(new ContractEvent(
                "BookingSagaFailed",
                new BookingSagaFailedPayload(failed.aggregateId(), journeyOrderIdForSaga(failed.aggregateId()), failed.reason())));
            case BookingEvent.BookingSagaAdvanced ignored -> Optional.empty();
            case BookingEvent.BookingSagaStepSucceeded ignored -> Optional.empty();
            case BookingEvent.BookingSagaStepFailed ignored -> Optional.empty();
            case BookingEvent.BookingSagaRetryScheduled ignored -> Optional.empty();
            case BookingEvent.BookingSagaManualReviewRequired ignored -> Optional.empty();
            case SegmentBookingEvent.ProviderReferenceAttached ignored -> Optional.empty();
            case SegmentBookingEvent.ProviderReservationTimedOut ignored -> Optional.empty();
            case SegmentBookingEvent.SegmentBookingCancelRequested ignored -> Optional.empty();
            case SegmentBookingEvent.ProviderConfirmationReceivedAfterCancellation ignored -> Optional.empty();
            case SegmentBookingEvent.ProviderCancellationRequired ignored -> Optional.empty();
        };
    }

    private String journeyOrderIdForSaga(String sagaId) {
        BookingSaga saga = sagas.findById(sagaId).orElse(null);
        if (saga == null) {
            throw new IllegalStateException("cannot publish saga event for unknown saga: " + sagaId);
        }
        return saga.journeyOrderId();
    }

    private SegmentBooking bySegmentBookingId(Map<String, Object> payload) {
        String segmentBookingId = segmentBookingReference(payload);
        return segmentBookingId == null ? null : segmentBookings.findById(segmentBookingId).map(SegmentBookingRepository.SegmentBookingRecord::booking).orElse(null);
    }

    private String segmentBookingReference(Map<String, Object> payload) {
        String direct = firstText(payload, "segmentBookingId", "segmentBookingRef");
        if (direct != null) {
            return direct;
        }
        if (payload.get("references") instanceof Map<?, ?> references) {
            return firstText(references, "segmentBookingRef", "segmentBookingId");
        }
        return null;
    }

    private void validateCapacityHeldContract(String eventType, Map<String, Object> payload) {
        if ("CapacityHoldConfirmed".equals(eventType)) {
            requirePayloadText(eventType, payload, "holdId", "inventoryPoolId", "capacityUnitRef", "confirmedAt");
        } else {
            requirePayloadText(eventType, payload, "holdId", "inventoryPoolId", "capacityUnitRef", "idempotencyKey", "expiresAt");
            requirePayloadBoolean(eventType, payload, "idempotentReplay");
        }
        requirePayloadObject(eventType, payload, "interval");
    }

    private void validateCapacityReleasedContract(String eventType, Map<String, Object> payload) {
        if ("CapacityHoldExpired".equals(eventType)) {
            requirePayloadText(eventType, payload, "holdId", "inventoryPoolId", "capacityUnitRef", "expiredAt");
        } else {
            requirePayloadText(eventType, payload, "holdId", "inventoryPoolId", "capacityUnitRef", "releasedAt", "releaseReason");
        }
        requirePayloadObject(eventType, payload, "interval");
    }

    private void requirePayloadText(String eventType, Map<String, Object> payload, String... fields) {
        for (String field : fields) {
            if (text(payload.get(field)) == null) {
                throw new IllegalArgumentException(eventType + " payload requires " + field);
            }
        }
    }

    private void requirePayloadBoolean(String eventType, Map<String, Object> payload, String field) {
        if (!(payload.get(field) instanceof Boolean)) {
            throw new IllegalArgumentException(eventType + " payload requires " + field);
        }
    }

    private void requirePayloadObject(String eventType, Map<String, Object> payload, String field) {
        if (!(payload.get(field) instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(eventType + " payload requires " + field);
        }
    }

    private boolean canApplyCapacityRelease(SegmentBooking booking) {
        return switch (booking.status()) {
            case REQUESTED, HOLDING, CANCEL_REQUESTED -> true;
            case CONFIRMED, TICKETED, IN_FULFILLMENT, COMPLETED, CANCELLED, CHANGE_REQUESTED, CHANGED, FAILED -> false;
        };
    }

    private void warnCapacityAckSkip(String eventType, String reason) {
        LOGGER.warn("Ack-skipping {} from capacity-availability: {}", eventType, reason);
    }

    private BookingSaga sagaByCorrelationId(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return null;
        }
        return sagas.findByCorrelationId(correlationId).orElse(null);
    }

    private List<SegmentBooking> segmentBookingsForSaga(String sagaId) {
        return segmentBookings.findBySagaId(sagaId).stream()
            .map(SegmentBookingRepository.SegmentBookingRecord::booking)
            .toList();
    }

    private SegmentBooking byHoldId(String holdId) {
        return segmentBookings.findByCapacityHoldId(holdId)
            .map(SegmentBookingRepository.SegmentBookingRecord::booking)
            .orElse(null);
    }

    private Optional<String> sagaIdForSegmentBooking(String segmentBookingId) {
        return segmentBookings.findById(segmentBookingId).map(SegmentBookingRepository.SegmentBookingRecord::sagaId);
    }

    private String sagaIdForPaymentIntent(String paymentIntentId, Map<String, Object> payload) {
        String sagaId = paymentIntentRefs.findSagaId(paymentIntentId).orElse(null);
        if (sagaId != null) {
            return sagaId;
        }
        String businessRef = firstText(payload, "businessRef", "journeyOrderId", "orderId");
        return businessRef == null ? null : mapPaymentIntent(paymentIntentId, businessRef).orElse(null);
    }

    private Optional<String> mapPaymentIntent(String paymentIntentId, String businessRef) {
        BookingSaga saga = sagaByBusinessRef(businessRef);
        if (saga != null) {
            paymentIntentRefs.saveIfAbsent(paymentIntentId, saga.sagaId());
            return Optional.of(saga.sagaId());
        }
        return Optional.empty();
    }

    private BookingSaga sagaByBusinessRef(String businessRef) {
        BookingSaga bySagaId = sagas.findById(businessRef).orElse(null);
        if (bySagaId != null) {
            return bySagaId;
        }
        return sagas.findByJourneyOrderId(businessRef).orElse(null);
    }

    private ProviderReference providerReference(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            String providerId = text(map.get("providerId"));
            String reservationId = firstText(map, "reservationId", "providerReservationId");
            String displayReference = firstText(map, "displayReference", "confirmationNo", "reference");
            return new ProviderReference(
                providerId == null ? "provider" : providerId,
                reservationId == null ? displayReference : reservationId,
                displayReference == null ? reservationId : displayReference);
        }
        String reference = text(raw);
        if (reference == null) {
            throw new IllegalArgumentException("providerReference is required");
        }
        return new ProviderReference("provider", reference, reference);
    }

    private static Map<String, Object> payloadMap(Object payload) {
        if (payload instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    result.put(key, entry.getValue());
                }
            }
            return result;
        }
        throw new IllegalArgumentException("event payload must be an object");
    }

    private static String firstText(Map<?, ?> payload, String... names) {
        for (String name : names) {
            String value = text(payload.get(name));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String text(Object value) {
        if (value instanceof String string && !string.isBlank()) {
            return string;
        }
        return null;
    }

    private static List<String> listOfText(Object raw) {
        List<String> values = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                String value = text(item);
                if (value != null) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private SagaDetail buildSagaDetail(BookingSaga saga) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (BookingSagaStep step : saga.steps()) {
            Map<String, Object> stepMap = new HashMap<>();
            stepMap.put("name", step.name());
            stepMap.put("idempotencyKey", step.idempotencyKey());
            stepMap.put("status", step.status().name());
            stepMap.put("attemptNumber", step.attemptNumber());
            step.failureReason().ifPresent(reason -> stepMap.put("failureReason", reason));
            steps.add(stepMap);
        }
        return new SagaDetail(saga.sagaId(), saga.journeyOrderId(), mapSagaStatus(saga.status()),
            saga.idempotencyKey(), steps, saga.terminalReason().orElse(null));
    }

    public static String mapSagaStatus(BookingSagaStatus status) {
        return switch (status) {
            case PLANNED -> "STARTED";
            case RESERVING -> "RESERVING";
            case AWAITING_PAYMENT -> "WAITING_PAYMENT";
            case CONFIRMING -> "HELD";
            case TICKETING -> "TICKETING";
            case COMPLETED -> "COMPLETED";
            case PARTIALLY_CONFIRMED -> "HELD";
            case COMPENSATING, MANUAL_REVIEW, FAILED -> "FAILED";
        };
    }

    record ContractEvent(String eventType, Object payload) {}
    public record BookingSagaStartedPayload(String sagaId, String journeyOrderId, Instant startedAt) {}
    public record SegmentReservationRequestedPayload(String segmentBookingId, String journeyOrderId, String segmentRef,
                                                     String travelerRef, String idempotencyKey) {}
    public record SegmentCapacityHoldingPayload(String segmentBookingId, String capacityHoldId) {}
    public record SegmentReservationConfirmedPayload(String segmentBookingId, ProviderReference providerReference,
                                                     String evidence) {}
    public record SegmentReservationFailedPayload(String segmentBookingId, String reason) {}
    public record SegmentBookingCancelledPayload(String segmentBookingId, String reason, String capacityHoldId) {}

    private String holdIdForSegmentBooking(String segmentBookingId) {
        return segmentBookings.findById(segmentBookingId)
            .flatMap(record -> record.booking().capacityHoldId())
            .orElse(null);
    }
    public record SegmentTicketedPayload(String segmentBookingId, String entitlementId) {}
    public record BookingSagaCompletedPayload(String sagaId, String journeyOrderId) {}
    public record BookingSagaFailedPayload(String sagaId, String journeyOrderId, String reason) {}

    public record StartSagaCommand(String journeyOrderId, String accountId, String offerId,
                                   List<String> travelerRefs, List<String> segmentRefs) {}
    public record StartSagaResult(String sagaId, String journeyOrderId, String status, Instant startedAt) {}
    public record RequestReservationCommand(String segmentRef, String travelerRef, String segmentBookingId) {}
    public record RequestReservationResult(String segmentBookingId, String status) {}
    public record MarkTicketedCommand(String segmentBookingId, String entitlementId) {}
    public record MarkTicketedResult(String segmentBookingId, String status) {}
    public record SagaDetail(String sagaId, String journeyOrderId, String status, String idempotencyKey,
                             List<Map<String, Object>> steps, String terminalReason) {}

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
    }

    public static class PreconditionFailedException extends RuntimeException {
        public PreconditionFailedException(String message, Throwable cause) { super(message, cause); }
    }
}
