package com.trainticket.postsales.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Objects;

public record CompensationMismatchCheckpoint(
    String caseId,
    String journeyOrderId,
    String caseStatus,
    String mismatchCode,
    String reason,
    String approvedCurrency,
    long approvedMinorUnits,
    JsonNode refundEvidence,
    JsonNode walletEvidence,
    JsonNode rightsEvidence,
    JsonNode operationEvidence,
    String correlationId,
    Instant detectedAt
) {
    public CompensationMismatchCheckpoint {
        caseId = required(caseId, "caseId");
        journeyOrderId = required(journeyOrderId, "journeyOrderId");
        caseStatus = required(caseStatus, "caseStatus");
        mismatchCode = required(mismatchCode, "mismatchCode");
        reason = required(reason, "reason");
        approvedCurrency = required(approvedCurrency, "approvedCurrency");
        refundEvidence = Objects.requireNonNull(refundEvidence, "refundEvidence is required").deepCopy();
        walletEvidence = Objects.requireNonNull(walletEvidence, "walletEvidence is required").deepCopy();
        rightsEvidence = Objects.requireNonNull(rightsEvidence, "rightsEvidence is required").deepCopy();
        operationEvidence = Objects.requireNonNull(operationEvidence, "operationEvidence is required").deepCopy();
        correlationId = required(correlationId, "correlationId");
        detectedAt = Objects.requireNonNull(detectedAt, "detectedAt is required");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
