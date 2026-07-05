package com.trainticket.platformkit.http;

import com.trainticket.platformkit.idempotency.IdempotencyKeyReusedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@ControllerAdvice
public class CanonicalExceptionHandler {
    private final HttpServletRequest request;

    public CanonicalExceptionHandler(HttpServletRequest request) {
        this.request = request;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> validation(MethodArgumentNotValidException exception) {
        return support().validation(exception);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorBody> validation(ConstraintViolationException exception) {
        return support().validation(exception);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorBody> missingHeader(MissingRequestHeaderException exception) {
        return support().missingHeader(exception);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> unreadable(HttpMessageNotReadableException exception) {
        return support().unreadable(exception);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorBody> typeMismatch(MethodArgumentTypeMismatchException exception) {
        return support().typeMismatch(exception);
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ResponseEntity<ErrorBody> idempotencyKeyReused(IdempotencyKeyReusedException exception) {
        return support().response(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED", exception.getMessage());
    }

    private CanonicalExceptionHandlerSupport support() {
        return new CanonicalExceptionHandlerSupport(request);
    }
}
