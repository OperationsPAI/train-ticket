package com.trainticket.travelerprofile.adapters.http;

import com.trainticket.travelerprofile.RequestContextFilter;
import com.trainticket.travelerprofile.application.IdempotencyKeyReusedException;
import com.trainticket.travelerprofile.application.TravelerNotFoundException;
import com.trainticket.travelerprofile.application.ValidationException;
import com.trainticket.travelerprofile.domain.DomainRuleViolation;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ValidationException.class)
    ResponseEntity<ApiError> validation(ValidationException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", exception.getMessage(), request, exception.details());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class})
    ResponseEntity<ApiError> malformed(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request, Map.of("request", exception.getMessage()));
    }

    @ExceptionHandler(TravelerNotFoundException.class)
    ResponseEntity<ApiError> notFound(TravelerNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ResponseEntity<ApiError> idempotency(IdempotencyKeyReusedException exception, HttpServletRequest request) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED", exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(DomainRuleViolation.class)
    ResponseEntity<ApiError> domain(DomainRuleViolation exception, HttpServletRequest request) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "DOMAIN_RULE_VIOLATION", exception.getMessage(), request, Map.of());
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message, HttpServletRequest request, Map<String, ?> details) {
        String correlationId = (String) request.getAttribute(RequestContextFilter.CORRELATION_ID_HEADER);
        if (correlationId == null) {
            correlationId = request.getHeader(RequestContextFilter.CORRELATION_ID_HEADER);
        }
        return ResponseEntity.status(status).body(new ApiError(code, message, correlationId, details));
    }
}
