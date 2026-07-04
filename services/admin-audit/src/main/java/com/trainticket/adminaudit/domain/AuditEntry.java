package com.trainticket.adminaudit.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable audit trail entry.
 * Once recorded, an entry cannot be modified.
 * Corrections create new entries that reference the original.
 */
public record AuditEntry(
    String entryId,
    String actorId,
    String actorDisplayName,
    String actionType,
    String resourceRef,
    String resourceDomain,
    String reasonCode,
    String correlationId,
    String resultSummary,
    String correctedEntryId,
    Instant recordedAt
) {
    public AuditEntry {
        entryId = requireText(entryId, "entryId");
        actorId = requireText(actorId, "actorId");
        actorDisplayName = requireText(actorDisplayName, "actorDisplayName");
        actionType = requireText(actionType, "actionType");
        resourceRef = requireText(resourceRef, "resourceRef");
        resourceDomain = requireText(resourceDomain, "resourceDomain");
        reasonCode = requireText(reasonCode, "reasonCode");
        correlationId = requireText(correlationId, "correlationId");
        resultSummary = resultSummary != null ? resultSummary : "";
        correctedEntryId = (correctedEntryId != null && correctedEntryId.isBlank()) ? null : correctedEntryId;
        Objects.requireNonNull(recordedAt, "recordedAt is required");
    }

    public boolean isCorrection() {
        return correctedEntryId != null && !correctedEntryId.isBlank();
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
        return value;
    }
}
