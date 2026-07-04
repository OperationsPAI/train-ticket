package com.trainticket.bookingorchestration.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class ReservationRequestLog {
    private final String logId;
    private final String segmentBookingId;
    private final String providerId;
    private final String operation;
    private final String idempotencyKey;
    private final int attemptNo;
    private boolean responseRecorded;
    private boolean timedOut;
    private String responseSummary;

    public ReservationRequestLog(
        String segmentBookingId,
        String providerId,
        String operation,
        String idempotencyKey,
        int attemptNo
    ) {
        this.logId = UUID.randomUUID().toString();
        this.segmentBookingId = requireText(segmentBookingId, "segmentBookingId");
        this.providerId = requireText(providerId, "providerId");
        this.operation = requireText(operation, "operation");
        this.idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        this.attemptNo = attemptNo;
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo must be >= 1");
        }
    }

    public String logId() { return logId; }
    public String segmentBookingId() { return segmentBookingId; }
    public String providerId() { return providerId; }
    public String operation() { return operation; }
    public String idempotencyKey() { return idempotencyKey; }
    public int attemptNo() { return attemptNo; }
    public boolean responseRecorded() { return responseRecorded; }
    public boolean timedOut() { return timedOut; }
    public String responseSummary() { return responseSummary; }

    public void recordResponse(String summary) {
        if (responseRecorded) {
            throw new IllegalStateException("response already recorded for reservation request log " + logId);
        }
        this.responseSummary = requireText(summary, "summary");
        this.responseRecorded = true;
    }

    public void recordTimeout() {
        if (responseRecorded) {
            throw new IllegalStateException("cannot record timeout after response already recorded");
        }
        this.timedOut = true;
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
