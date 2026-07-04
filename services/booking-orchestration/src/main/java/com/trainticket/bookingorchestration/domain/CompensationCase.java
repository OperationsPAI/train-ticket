package com.trainticket.bookingorchestration.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class CompensationCase {
    private final String compensationCaseId;
    private final String sagaId;
    private final String compensationReason;
    private boolean closed;
    private boolean escalated;
    private int providerCancellationRequests;
    private int holdReleaseRequests;
    private int refundRequests;

    private CompensationCase(String sagaId, String compensationReason) {
        this.compensationCaseId = UUID.randomUUID().toString();
        this.sagaId = requireText(sagaId, "sagaId");
        this.compensationReason = requireText(compensationReason, "compensationReason");
        this.closed = false;
        this.escalated = false;
    }

    public static CompensationCase open(String sagaId, String compensationReason, Instant now) {
        return new CompensationCase(sagaId, compensationReason);
    }

    public String compensationCaseId() { return compensationCaseId; }
    public String sagaId() { return sagaId; }
    public String compensationReason() { return compensationReason; }
    public boolean isClosed() { return closed; }
    public boolean isEscalated() { return escalated; }
    public int providerCancellationRequests() { return providerCancellationRequests; }
    public int holdReleaseRequests() { return holdReleaseRequests; }
    public int refundRequests() { return refundRequests; }

    public void requestProviderCancellation(String providerReference) {
        requireOpen();
        providerCancellationRequests++;
    }

    public void requestHoldRelease(String holdId) {
        requireOpen();
        holdReleaseRequests++;
    }

    public void requestRefund(String paymentIntentId) {
        requireOpen();
        refundRequests++;
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
    }

    public void escalate(String reason) {
        requireOpen();
        escalated = true;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("compensation case " + compensationCaseId + " is already closed");
        }
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
