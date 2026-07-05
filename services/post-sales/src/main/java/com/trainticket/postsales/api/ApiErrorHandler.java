package com.trainticket.postsales.api;

import com.trainticket.postsales.application.CaseNotFoundException;
import com.trainticket.postsales.application.IdempotencyKeyReusedException;
import com.trainticket.postsales.domain.DomainRuleViolation;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrorHandler {
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, MissingRequestHeaderException.class, IllegalArgumentException.class})
    ResponseEntity<ErrorBody> validation(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", exception.getMessage(), request);
    }

    @ExceptionHandler(CaseNotFoundException.class)
    ResponseEntity<ErrorBody> notFound(CaseNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ResponseEntity<ErrorBody> idempotency(IdempotencyKeyReusedException exception, HttpServletRequest request) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED", exception.getMessage(), request);
    }

    @ExceptionHandler(DomainRuleViolation.class)
    ResponseEntity<ErrorBody> domain(DomainRuleViolation exception, HttpServletRequest request) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "DOMAIN_RULE_VIOLATION", exception.getMessage(), request);
    }

    private static ResponseEntity<ErrorBody> error(HttpStatus status, String code, String message, HttpServletRequest request) {
        String correlationId = (String) request.getAttribute("correlationId");
        if (correlationId == null) {
            correlationId = request.getHeader("X-Correlation-Id");
        }
        return ResponseEntity.status(status).body(new ErrorBody(code, message, correlationId, Map.of()));
    }

    public record ErrorBody(String code, String message, String correlationId, Map<String, Object> details) { }
}
