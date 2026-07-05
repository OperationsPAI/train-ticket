package com.trainticket.platformkit.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

public class CanonicalExceptionHandlerSupport {
    private final HttpServletRequest request;
    public CanonicalExceptionHandlerSupport(HttpServletRequest request) { this.request = request; }
    public ResponseEntity<ErrorBody> validation(MethodArgumentNotValidException ex) { return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed"); }
    public ResponseEntity<ErrorBody> validation(ConstraintViolationException ex) { return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", ex.getMessage()); }
    public ResponseEntity<ErrorBody> missingHeader(MissingRequestHeaderException ex) { return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Missing required header: " + ex.getHeaderName()); }
    public ResponseEntity<ErrorBody> unreadable(HttpMessageNotReadableException ex) { return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Malformed JSON request"); }
    public ResponseEntity<ErrorBody> typeMismatch(MethodArgumentTypeMismatchException ex) { return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Invalid parameter: " + ex.getName()); }
    public ResponseEntity<ErrorBody> response(HttpStatus status, String code, String message) { return ResponseEntity.status(status).body(ErrorBody.of(code, message, correlationId())); }
    public ResponseEntity<ErrorBody> response(HttpStatus status, String code, String message, Map<String, Object> details) { return ResponseEntity.status(status).body(ErrorBody.of(code, message, correlationId(), details)); }
    private String correlationId() { Object value = request == null ? null : request.getAttribute("correlationId"); if (value == null && request != null) value = request.getHeader("X-Correlation-Id"); return value == null ? "" : value.toString(); }
}
