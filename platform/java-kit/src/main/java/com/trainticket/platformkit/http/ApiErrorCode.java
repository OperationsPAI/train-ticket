package com.trainticket.platformkit.http;

import org.springframework.http.HttpStatus;

public enum ApiErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    CONFLICT(HttpStatus.CONFLICT),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY),
    PRECONDITION_FAILED(HttpStatus.PRECONDITION_FAILED),
    DOMAIN_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY),
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE);

    private final HttpStatus status;

    ApiErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
