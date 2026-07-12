package com.trainticket.platformkit.http;

import com.trainticket.platformkit.idempotency.IdempotencyKeyReusedException;
import com.trainticket.platformkit.persistence.OptimisticConcurrencyException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class PlatformKitExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException exception, HttpServletRequest request) {
        return error(exception.code(), exception.getMessage(), request, exception.details());
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ResponseEntity<ApiError> handleIdempotencyReuse(IdempotencyKeyReusedException exception, HttpServletRequest request) {
        return error(ApiErrorCode.IDEMPOTENCY_KEY_REUSED, exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(OptimisticConcurrencyException.class)
    public ResponseEntity<ApiError> handleOptimisticConcurrency(OptimisticConcurrencyException exception, HttpServletRequest request) {
        return error(ApiErrorCode.CONFLICT, exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler({IllegalArgumentException.class, MissingRequestHeaderException.class})
    public ResponseEntity<ApiError> handleValidation(RuntimeException exception, HttpServletRequest request) {
        return error(ApiErrorCode.VALIDATION_FAILED, exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBodyValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return error(ApiErrorCode.VALIDATION_FAILED, "Request validation failed", request, Map.of("fields", fieldErrors));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handleMethodValidation(HandlerMethodValidationException exception, HttpServletRequest request) {
        return error(ApiErrorCode.VALIDATION_FAILED, "Request validation failed", request, Map.of("errors", exception.getAllErrors().stream()
            .map(error -> error.getDefaultMessage() == null ? error.toString() : error.getDefaultMessage())
            .toList()));
    }

    private ResponseEntity<ApiError> error(ApiErrorCode code, String message, HttpServletRequest request, Map<String, ?> details) {
        return ResponseEntity.status(code.status()).body(new ApiError(code.name(), message, CorrelationIds.from(request), details));
    }
}
