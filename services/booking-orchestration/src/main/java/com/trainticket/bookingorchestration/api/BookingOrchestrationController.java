package com.trainticket.bookingorchestration.api;

import com.trainticket.bookingorchestration.application.EventEnvelope;
import com.trainticket.bookingorchestration.application.EventPublisher;
import com.trainticket.bookingorchestration.domain.BookingSaga;
import com.trainticket.bookingorchestration.domain.BookingSagaStatus;
import com.trainticket.bookingorchestration.domain.BookingSagaStep;
import com.trainticket.bookingorchestration.domain.DomainEvent;
import com.trainticket.bookingorchestration.domain.SegmentBooking;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal")
public class BookingOrchestrationController {

    private final Clock clock;
    private final EventPublisher eventPublisher;
    private final IdempotencyStore idempotencyStore;
    private final SagaStore sagaStore;

    public BookingOrchestrationController(Clock clock, EventPublisher eventPublisher) {
        this.clock = clock;
        this.eventPublisher = eventPublisher;
        this.idempotencyStore = new IdempotencyStore();
        this.sagaStore = new SagaStore();
    }

    BookingOrchestrationController(Clock clock, EventPublisher eventPublisher,
                                   IdempotencyStore idempotencyStore, SagaStore sagaStore) {
        this.clock = clock;
        this.eventPublisher = eventPublisher;
        this.idempotencyStore = idempotencyStore;
        this.sagaStore = sagaStore;
    }

    // ---- DTOs ----

    public record StartSagaRequest(
        String journeyOrderId, String accountId, String offerId,
        List<String> travelerRefs, List<String> segmentRefs
    ) {}

    public record StartSagaResponse(
        String sagaId, String journeyOrderId, String status, Instant startedAt
    ) {}

    public record RequestReservationRequest(
        String segmentRef, String travelerRef, String segmentBookingId
    ) {}

    public record RequestReservationResponse(
        String segmentBookingId, String status
    ) {}

    public record MarkTicketedRequest(
        String segmentBookingId, String entitlementId
    ) {}

    public record MarkTicketedResponse(
        String segmentBookingId, String status
    ) {}

    public record ErrorBody(
        String code, String message, String correlationId, Map<String, Object> details
    ) {}

    // ---- Start Booking Saga ----

    @PostMapping(value = "/booking-sagas", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> startSaga(
            @RequestBody StartSagaRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            HttpServletRequest httpRequest) {
        List<String> errors = validateStartSaga(request);
        if (!errors.isEmpty()) {
            return validationError(errors, httpRequest);
        }

        var existing = idempotencyStore.get(idempotencyKey, request);
        if (existing != null) {
            return ResponseEntity.status(HttpStatus.CREATED).body(existing);
        }

        String sagaId = "saga-" + UUID.randomUUID();
        List<BookingSagaStep> plan = new ArrayList<>();
        for (String segmentRef : request.segmentRefs()) {
            plan.add(BookingSaga.stepPlan(
                "reserve-" + segmentRef, sagaId + ":" + segmentRef,
                Duration.ofMinutes(5), 3, "release-capacity"));
        }

        BookingSaga saga = BookingSaga.start(sagaId, request.journeyOrderId(), "1",
            "purchase", plan, clock);

        for (DomainEvent event : saga.pullEvents()) {
            publishEvent(event, sagaId, httpRequest);
        }

        sagaStore.put(sagaId, saga);
        var response = new StartSagaResponse(sagaId, request.journeyOrderId(),
            mapSagaStatus(saga.status()), clock.instant());
        idempotencyStore.put(idempotencyKey, request, response);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // ---- Get Saga Status ----

    @GetMapping(value = "/booking-sagas/{sagaId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getSaga(@PathVariable String sagaId, HttpServletRequest httpRequest) {
        var sagaOpt = sagaStore.get(sagaId);
        if (sagaOpt.isEmpty()) {
            return notFound("Booking saga not found: " + sagaId, httpRequest);
        }
        return ResponseEntity.ok(buildSagaDetail(sagaOpt.get()));
    }

    // ---- Request Segment Reservation ----

    @PostMapping(value = "/booking-sagas/{sagaId}/request-reservation",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> requestReservation(
            @PathVariable String sagaId,
            @RequestBody RequestReservationRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            HttpServletRequest httpRequest) {
        Optional<BookingSaga> sagaOpt = sagaStore.get(sagaId);
        if (sagaOpt.isEmpty()) {
            return notFound("Booking saga not found: " + sagaId, httpRequest);
        }

        List<String> errors = validateRequestReservation(request);
        if (!errors.isEmpty()) {
            return validationError(errors, httpRequest);
        }

        var existing = idempotencyStore.get(idempotencyKey, request);
        if (existing != null) {
            return ResponseEntity.ok(existing);
        }

        SegmentBooking segmentBooking = SegmentBooking.requestReservation(
            request.segmentBookingId(), sagaOpt.get().journeyOrderId(),
            request.segmentRef(), request.segmentRef(),
            request.travelerRef(), "purchase", clock);

        for (DomainEvent event : segmentBooking.pullEvents()) {
            publishEvent(event, sagaId, httpRequest);
        }

        var response = new RequestReservationResponse(request.segmentBookingId(), "REQUESTED");
        idempotencyStore.put(idempotencyKey, request, response);
        return ResponseEntity.ok(response);
    }

    // ---- Mark Segment Ticketed ----

    @PostMapping(value = "/booking-sagas/{sagaId}/mark-ticketed",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> markTicketed(
            @PathVariable String sagaId,
            @RequestBody MarkTicketedRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            HttpServletRequest httpRequest) {
        Optional<BookingSaga> sagaOpt = sagaStore.get(sagaId);
        if (sagaOpt.isEmpty()) {
            return notFound("Booking saga not found: " + sagaId, httpRequest);
        }

        List<String> errors = validateMarkTicketed(request);
        if (!errors.isEmpty()) {
            return validationError(errors, httpRequest);
        }

        var existing = idempotencyStore.get(idempotencyKey, request);
        if (existing != null) {
            return ResponseEntity.ok(existing);
        }

        var response = new MarkTicketedResponse(request.segmentBookingId(), "TICKETED");
        idempotencyStore.put(idempotencyKey, request, response);
        return ResponseEntity.ok(response);
    }

    // ---- Error handling ----

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorBody> handleIllegalArgument(IllegalArgumentException ex,
                                                           HttpServletRequest httpRequest) {
        String correlationId = getCorrelationId(httpRequest);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(new ErrorBody("DOMAIN_RULE_VIOLATION", ex.getMessage(), correlationId, Map.of()));
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ResponseEntity<ErrorBody> handleIdempotencyKeyReused(IdempotencyKeyReusedException ex,
                                                                    HttpServletRequest httpRequest) {
        return idempotencyKeyReused(httpRequest);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorBody> handleIllegalState(IllegalStateException ex,
                                                        HttpServletRequest httpRequest) {
        String correlationId = getCorrelationId(httpRequest);
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ErrorBody("CONFLICT", ex.getMessage(), correlationId, Map.of()));
    }

    // ---- Internal helpers ----

    private ResponseEntity<ErrorBody> validationError(List<String> errors, HttpServletRequest request) {
        String correlationId = getCorrelationId(request);
        return ResponseEntity.badRequest()
            .body(new ErrorBody("VALIDATION_FAILED", String.join("; ", errors), correlationId, Map.of()));
    }

    private ResponseEntity<ErrorBody> notFound(String message, HttpServletRequest request) {
        String correlationId = getCorrelationId(request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ErrorBody("NOT_FOUND", message, correlationId, Map.of()));
    }

    private ResponseEntity<ErrorBody> idempotencyKeyReused(HttpServletRequest request) {
        String correlationId = getCorrelationId(request);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(new ErrorBody("IDEMPOTENCY_KEY_REUSED",
                "Idempotency-Key was reused with a different request body",
                correlationId, Map.of()));
    }

    private String getCorrelationId(HttpServletRequest request) {
        String id = request.getHeader("X-Correlation-Id");
        return id != null && !id.isBlank() ? id : UUID.randomUUID().toString();
    }

    private void publishEvent(DomainEvent event, String sagaId, HttpServletRequest request) {
        EventEnvelope envelope = new EventEnvelope(
            "evt-" + UUID.randomUUID(), event.getClass().getSimpleName(), 1,
            "booking-orchestration", "cmd-" + UUID.randomUUID(),
            getCorrelationId(request), clock.instant(), event);
        eventPublisher.publish(envelope);
    }

    private List<String> validateStartSaga(StartSagaRequest request) {
        List<String> errors = new ArrayList<>();
        if (request.journeyOrderId() == null || request.journeyOrderId().isBlank()) {
            errors.add("journeyOrderId is required");
        }
        if (request.accountId() == null || request.accountId().isBlank()) {
            errors.add("accountId is required");
        }
        if (request.offerId() == null || request.offerId().isBlank()) {
            errors.add("offerId is required");
        }
        if (request.travelerRefs() == null || request.travelerRefs().isEmpty()) {
            errors.add("travelerRefs must not be empty");
        }
        if (request.segmentRefs() == null || request.segmentRefs().isEmpty()) {
            errors.add("segmentRefs must not be empty");
        }
        return errors;
    }

    private List<String> validateRequestReservation(RequestReservationRequest request) {
        List<String> errors = new ArrayList<>();
        if (request.segmentRef() == null || request.segmentRef().isBlank()) {
            errors.add("segmentRef is required");
        }
        if (request.travelerRef() == null || request.travelerRef().isBlank()) {
            errors.add("travelerRef is required");
        }
        if (request.segmentBookingId() == null || request.segmentBookingId().isBlank()) {
            errors.add("segmentBookingId is required");
        }
        return errors;
    }

    private List<String> validateMarkTicketed(MarkTicketedRequest request) {
        List<String> errors = new ArrayList<>();
        if (request.segmentBookingId() == null || request.segmentBookingId().isBlank()) {
            errors.add("segmentBookingId is required");
        }
        if (request.entitlementId() == null || request.entitlementId().isBlank()) {
            errors.add("entitlementId is required");
        }
        return errors;
    }

    private String mapSagaStatus(BookingSagaStatus status) {
        return switch (status) {
            case PLANNED -> "PLANNED";
            case RESERVING -> "RESERVING";
            case AWAITING_PAYMENT -> "WAITING_PAYMENT";
            case CONFIRMING -> "CONFIRMING";
            case TICKETING -> "TICKETING";
            case COMPLETED -> "COMPLETED";
            case PARTIALLY_CONFIRMED -> "PARTIALLY_CONFIRMED";
            case COMPENSATING -> "COMPENSATING";
            case MANUAL_REVIEW -> "MANUAL_REVIEW";
            case FAILED -> "FAILED";
        };
    }

    private Map<String, Object> buildSagaDetail(BookingSaga saga) {
        Map<String, Object> detail = new HashMap<>();
        detail.put("sagaId", saga.sagaId());
        detail.put("journeyOrderId", saga.journeyOrderId());
        detail.put("status", mapSagaStatus(saga.status()));
        detail.put("idempotencyKey", saga.idempotencyKey());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (BookingSagaStep step : saga.steps()) {
            Map<String, Object> stepMap = new HashMap<>();
            stepMap.put("name", step.name());
            stepMap.put("idempotencyKey", step.idempotencyKey());
            stepMap.put("status", step.status().name());
            stepMap.put("attemptNumber", step.attemptNumber());
            step.failureReason().ifPresent(r -> stepMap.put("failureReason", r));
            steps.add(stepMap);
        }
        detail.put("steps", steps);
        saga.terminalReason().ifPresent(r -> detail.put("terminalReason", r));
        return detail;
    }

    static class IdempotencyStore {
        private static final class Entry {
            final Object requestBody;
            final Object response;
            Entry(Object requestBody, Object response) {
                this.requestBody = requestBody;
                this.response = response;
            }
        }
        private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

        void put(String key, Object requestBody, Object response) {
            store.put(key, new Entry(requestBody, response));
        }

        Object get(String key, Object requestBody) {
            Entry entry = store.get(key);
            if (entry == null) {
                return null;
            }
            if (!requestBody.equals(entry.requestBody)) {
                throw new IdempotencyKeyReusedException();
            }
            return entry.response;
        }
    }

    static class SagaStore {
        private final ConcurrentHashMap<String, BookingSaga> store = new ConcurrentHashMap<>();
        void put(String key, BookingSaga saga) { store.put(key, saga); }
        Optional<BookingSaga> get(String key) { return Optional.ofNullable(store.get(key)); }
    }

    static class IdempotencyKeyReusedException extends RuntimeException {
        IdempotencyKeyReusedException() {
            super("Idempotency-Key was reused with a different request body");
        }
    }
}
