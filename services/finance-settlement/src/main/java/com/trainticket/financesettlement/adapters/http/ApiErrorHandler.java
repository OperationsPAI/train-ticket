package com.trainticket.financesettlement.adapters.http;

import com.trainticket.financesettlement.RequestContextFilter;
import com.trainticket.financesettlement.application.ResourceNotFoundException;
import com.trainticket.financesettlement.application.ValidationException;
import com.trainticket.financesettlement.domain.DomainRuleViolation;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrorHandler {
    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> notFound(ResourceNotFoundException ex, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request, Map.of());
    }

    @ExceptionHandler(DomainRuleViolation.class)
    ResponseEntity<ApiError> domainRuleViolation(DomainRuleViolation ex, HttpServletRequest request) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "DOMAIN_RULE_VIOLATION", ex.getMessage(), request, Map.of());
    }

    @ExceptionHandler({ValidationException.class, MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
        MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, MissingRequestHeaderException.class, IllegalArgumentException.class})
    ResponseEntity<ApiError> validation(Exception ex, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", ex.getMessage(), request, Map.of());
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message, HttpServletRequest request, Map<String, Object> details) {
        return ResponseEntity.status(status).body(new ApiError(code, message, correlationId(request), details));
    }

    private static String correlationId(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestContextFilter.CORRELATION_ID_HEADER);
        if (attribute instanceof String value && !value.isBlank()) {
            return value;
        }
        String header = request.getHeader(RequestContextFilter.CORRELATION_ID_HEADER);
        return header == null || header.isBlank() ? "" : header;
    }
}
