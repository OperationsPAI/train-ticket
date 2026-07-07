package com.trainticket.bookingorchestration.domain;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public final class BookingSagaStep {
    private final String name;
    private final String idempotencyKey;
    private final Duration timeout;
    private final int retryLimit;
    private final String compensationAction;
    private BookingStepStatus status = BookingStepStatus.PENDING;
    private int attemptNumber;
    private String failureReason;

    public BookingSagaStep(String name, String idempotencyKey, Duration timeout, int retryLimit,
                           String compensationAction) {
        this.name = requireText(name, "name");
        this.idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (retryLimit < 0) {
            throw new IllegalArgumentException("retryLimit must be zero or greater");
        }
        this.timeout = timeout;
        this.retryLimit = retryLimit;
        this.compensationAction = requireText(compensationAction, "compensationAction");
    }

    public static BookingSagaStep rehydrate(String name, String idempotencyKey, Duration timeout, int retryLimit,
                                             String compensationAction, BookingStepStatus status, int attemptNumber,
                                             String failureReason) {
        BookingSagaStep step = new BookingSagaStep(name, idempotencyKey, timeout, retryLimit, compensationAction);
        if (attemptNumber < 0) {
            throw new IllegalArgumentException("attemptNumber must be zero or greater");
        }
        step.status = Objects.requireNonNull(status, "status is required");
        step.attemptNumber = attemptNumber;
        step.failureReason = failureReason;
        return step;
    }

    public String name() {
        return name;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public Duration timeout() {
        return timeout;
    }

    public int retryLimit() {
        return retryLimit;
    }

    public String compensationAction() {
        return compensationAction;
    }

    public BookingStepStatus status() {
        return status;
    }

    public int attemptNumber() {
        return attemptNumber;
    }

    public Optional<String> failureReason() {
        return Optional.ofNullable(failureReason);
    }

    void succeed() {
        status = BookingStepStatus.SUCCEEDED;
        failureReason = null;
    }

    void fail(String reason) {
        status = BookingStepStatus.FAILED;
        failureReason = requireText(reason, "reason");
    }

    boolean canRetry() {
        return status == BookingStepStatus.FAILED && attemptNumber < retryLimit;
    }

    void scheduleRetry() {
        if (!canRetry()) {
            throw new IllegalStateException("retry limit reached for step " + name);
        }
        attemptNumber++;
        status = BookingStepStatus.PENDING;
        failureReason = null;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BookingSagaStep that)) {
            return false;
        }
        return idempotencyKey.equals(that.idempotencyKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(idempotencyKey);
    }
}
