package com.trainticket.adminaudit.adapters.http;

import com.trainticket.adminaudit.RequestContextFilter;
import com.trainticket.adminaudit.application.ConflictException;
import com.trainticket.adminaudit.application.NotFoundException;
import com.trainticket.adminaudit.application.PreconditionFailedException;
import com.trainticket.adminaudit.application.ValidationException;
import com.trainticket.adminaudit.application.ports.PublishFailedException;
import com.trainticket.adminaudit.domain.DomainRuleViolation;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler({ValidationException.class, IllegalArgumentException.class, HttpMessageNotReadableException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiError validation(Exception exception, HttpServletRequest request) {
        return error("VALIDATION_FAILED", exception.getMessage(), request);
    }

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ApiError notFound(Exception exception, HttpServletRequest request) {
        return error("NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ApiError conflict(Exception exception, HttpServletRequest request) {
        return error("CONFLICT", exception.getMessage(), request);
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    ApiError idempotency(Exception exception, HttpServletRequest request) {
        return error("IDEMPOTENCY_KEY_REUSED", exception.getMessage(), request);
    }

    @ExceptionHandler(PreconditionFailedException.class)
    @ResponseStatus(HttpStatus.PRECONDITION_FAILED)
    ApiError precondition(Exception exception, HttpServletRequest request) {
        return error("PRECONDITION_FAILED", exception.getMessage(), request);
    }

    @ExceptionHandler(DomainRuleViolation.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    ApiError domain(Exception exception, HttpServletRequest request) {
        return error("DOMAIN_RULE_VIOLATION", exception.getMessage(), request);
    }

    @ExceptionHandler(PublishFailedException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ApiError unavailable(Exception exception, HttpServletRequest request) {
        return error("UNAVAILABLE", exception.getMessage(), request);
    }

    private static ApiError error(String code, String message, HttpServletRequest request) {
        Object correlationId = request.getAttribute(RequestContextFilter.CORRELATION_ID_HEADER);
        return new ApiError(code, message == null ? code : message, correlationId == null ? "" : correlationId.toString(), Map.of());
    }
}
