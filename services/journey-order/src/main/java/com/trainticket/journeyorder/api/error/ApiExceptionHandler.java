package com.trainticket.journeyorder.api.error;

import com.trainticket.journeyorder.api.dto.ErrorBody;
import com.trainticket.journeyorder.application.service.OrderManagementService;
import com.trainticket.journeyorder.domain.DomainRuleViolation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private final HttpServletRequest request;

    public ApiExceptionHandler(HttpServletRequest request) {
        this.request = request;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException ex) {
        String details = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .collect(Collectors.joining(", "));
        return buildResponse(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorBody> handleConstraintViolation(ConstraintViolationException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", ex.getMessage());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorBody> handleMissingRequestHeader(MissingRequestHeaderException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Missing required header: " + ex.getHeaderName());
    }

    @ExceptionHandler(OrderManagementService.NotFoundException.class)
    public ResponseEntity<ErrorBody> handleNotFound(OrderManagementService.NotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(DomainRuleViolation.class)
    public ResponseEntity<ErrorBody> handleDomainRuleViolation(DomainRuleViolation ex) {
        return buildResponse(HttpStatus.UNPROCESSABLE_ENTITY, "DOMAIN_RULE_VIOLATION", ex.getMessage());
    }

    @ExceptionHandler({IdempotencyKeyReusedException.class, OrderManagementService.IdempotencyKeyReused.class})
    public ResponseEntity<ErrorBody> handleIdempotencyKeyReused(RuntimeException ex) {
        return buildResponse(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorBody> handleIllegalArgument(IllegalArgumentException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> handleGeneric(Exception ex) {
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error");
    }

    private ResponseEntity<ErrorBody> buildResponse(HttpStatus status, String code, String message) {
        String correlationId = MDC.get("correlationId");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = request.getHeader("X-Correlation-Id");
        }
        return ResponseEntity.status(status)
            .body(ErrorBody.of(code, message, correlationId != null ? correlationId : ""));
    }
}
