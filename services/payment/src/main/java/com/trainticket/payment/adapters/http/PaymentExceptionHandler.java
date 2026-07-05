package com.trainticket.payment.adapters.http;

import com.trainticket.payment.RequestContextFilter;
import com.trainticket.payment.application.NotFoundException;
import com.trainticket.payment.application.PublishFailedException;
import com.trainticket.payment.domain.DomainRuleViolation;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class PaymentExceptionHandler {
    @ExceptionHandler({ValidationException.class, HttpMessageNotReadableException.class, IllegalArgumentException.class})
    ResponseEntity<ErrorResponse> validation(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", exception.getMessage(), request);
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(NotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ResponseEntity<ErrorResponse> idempotency(IdempotencyKeyReusedException exception, HttpServletRequest request) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED", exception.getMessage(), request);
    }

    @ExceptionHandler(DomainRuleViolation.class)
    ResponseEntity<ErrorResponse> domain(DomainRuleViolation exception, HttpServletRequest request) {
        String code = exception.getMessage() != null && exception.getMessage().contains("cannot") ? "PRECONDITION_FAILED" : "DOMAIN_RULE_VIOLATION";
        HttpStatus status = "PRECONDITION_FAILED".equals(code) ? HttpStatus.PRECONDITION_FAILED : HttpStatus.UNPROCESSABLE_ENTITY;
        return error(status, code, exception.getMessage(), request);
    }

    @ExceptionHandler(PublishFailedException.class)
    ResponseEntity<ErrorResponse> unavailable(PublishFailedException exception, HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "UNAVAILABLE", exception.getMessage(), request);
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, message, correlationId(request), Map.of()));
    }

    private static String correlationId(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestContextFilter.CORRELATION_ID_ATTRIBUTE);
        if (attribute != null) {
            return attribute.toString();
        }
        String header = request.getHeader(RequestContextFilter.CORRELATION_ID_HEADER);
        return header == null ? "" : header;
    }
}
