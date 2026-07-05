package com.trainticket.bookingorchestration.api;

import com.trainticket.bookingorchestration.RequestContextFilter;
import com.trainticket.bookingorchestration.application.BookingOrchestrationService;
import com.trainticket.bookingorchestration.application.BookingOrchestrationService.IdempotencyKeyReusedException;
import com.trainticket.bookingorchestration.application.BookingOrchestrationService.MarkTicketedCommand;
import com.trainticket.bookingorchestration.application.BookingOrchestrationService.NotFoundException;
import com.trainticket.bookingorchestration.application.BookingOrchestrationService.PreconditionFailedException;
import com.trainticket.bookingorchestration.application.BookingOrchestrationService.RequestReservationCommand;
import com.trainticket.bookingorchestration.application.BookingOrchestrationService.StartSagaCommand;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    private final BookingOrchestrationService service;

    public BookingOrchestrationController(BookingOrchestrationService service) {
        this.service = service;
    }

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

    @PostMapping(value = "/booking-sagas", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> startSaga(
            @RequestBody StartSagaRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {
        List<String> errors = validateIdempotencyKey(idempotencyKey);
        errors.addAll(validateStartSaga(request));
        if (!errors.isEmpty()) {
            return validationError(errors, httpRequest);
        }

        var result = service.startSaga(new StartSagaCommand(request.journeyOrderId(), request.accountId(),
            request.offerId(), request.travelerRefs(), request.segmentRefs()), idempotencyKey, getCorrelationId(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(new StartSagaResponse(result.sagaId(), result.journeyOrderId(), result.status(), result.startedAt()));
    }

    @GetMapping(value = "/booking-sagas/{sagaId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getSaga(@PathVariable String sagaId, HttpServletRequest httpRequest) {
        return service.getSaga(sagaId)
            .<ResponseEntity<?>>map(ResponseEntity::ok)
            .orElseGet(() -> notFound("Booking saga not found: " + sagaId, httpRequest));
    }

    @PostMapping(value = "/booking-sagas/{sagaId}/request-reservation",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> requestReservation(
            @PathVariable String sagaId,
            @RequestBody RequestReservationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {
        List<String> errors = validateIdempotencyKey(idempotencyKey);
        errors.addAll(validateRequestReservation(request));
        if (!errors.isEmpty()) {
            return validationError(errors, httpRequest);
        }

        var result = service.requestReservation(sagaId,
            new RequestReservationCommand(request.segmentRef(), request.travelerRef(), request.segmentBookingId()),
            idempotencyKey, getCorrelationId(httpRequest));
        return ResponseEntity.ok(new RequestReservationResponse(result.segmentBookingId(), result.status()));
    }

    @PostMapping(value = "/booking-sagas/{sagaId}/mark-ticketed",
                 consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> markTicketed(
            @PathVariable String sagaId,
            @RequestBody MarkTicketedRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {
        List<String> errors = validateIdempotencyKey(idempotencyKey);
        errors.addAll(validateMarkTicketed(request));
        if (!errors.isEmpty()) {
            return validationError(errors, httpRequest);
        }

        var result = service.markTicketed(sagaId,
            new MarkTicketedCommand(request.segmentBookingId(), request.entitlementId()),
            idempotencyKey, getCorrelationId(httpRequest));
        return ResponseEntity.ok(new MarkTicketedResponse(result.segmentBookingId(), result.status()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorBody> handleIllegalArgument(IllegalArgumentException ex,
                                                           HttpServletRequest httpRequest) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "DOMAIN_RULE_VIOLATION", ex.getMessage(), httpRequest);
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ResponseEntity<ErrorBody> handleIdempotencyKeyReused(IdempotencyKeyReusedException ex,
                                                                HttpServletRequest httpRequest) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
            "Idempotency-Key was reused with a different request body", httpRequest);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorBody> handleNotFound(NotFoundException ex, HttpServletRequest httpRequest) {
        return notFound(ex.getMessage(), httpRequest);
    }

    @ExceptionHandler(PreconditionFailedException.class)
    public ResponseEntity<ErrorBody> handlePreconditionFailed(PreconditionFailedException ex,
                                                              HttpServletRequest httpRequest) {
        return error(HttpStatus.PRECONDITION_FAILED, "PRECONDITION_FAILED", ex.getMessage(), httpRequest);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorBody> handleIllegalState(IllegalStateException ex,
                                                        HttpServletRequest httpRequest) {
        return error(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage(), httpRequest);
    }

    private ResponseEntity<ErrorBody> validationError(List<String> errors, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", String.join("; ", errors), request);
    }

    private ResponseEntity<ErrorBody> notFound(String message, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", message, request);
    }

    private ResponseEntity<ErrorBody> error(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
            .body(new ErrorBody(code, message, getCorrelationId(request), Map.of()));
    }

    private String getCorrelationId(HttpServletRequest request) {
        Object requestScoped = request.getAttribute(RequestContextFilter.CORRELATION_ID_HEADER);
        if (requestScoped instanceof String id && !id.isBlank()) {
            return id;
        }
        String header = request.getHeader(RequestContextFilter.CORRELATION_ID_HEADER);
        if (header != null && !header.isBlank()) {
            return header;
        }
        String requestId = request.getHeader(RequestContextFilter.REQUEST_ID_HEADER);
        return requestId != null && !requestId.isBlank() ? requestId : "corr-" + UUID.randomUUID();
    }

    private List<String> validateIdempotencyKey(String idempotencyKey) {
        List<String> errors = new ArrayList<>();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            errors.add("Idempotency-Key header is required");
        }
        return errors;
    }

    private List<String> validateStartSaga(StartSagaRequest request) {
        List<String> errors = new ArrayList<>();
        if (request == null) {
            errors.add("request body is required");
            return errors;
        }
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
        if (request == null) {
            errors.add("request body is required");
            return errors;
        }
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
        if (request == null) {
            errors.add("request body is required");
            return errors;
        }
        if (request.segmentBookingId() == null || request.segmentBookingId().isBlank()) {
            errors.add("segmentBookingId is required");
        }
        if (request.entitlementId() == null || request.entitlementId().isBlank()) {
            errors.add("entitlementId is required");
        }
        return errors;
    }
}
