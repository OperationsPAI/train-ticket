package com.trainticket.adminaudit.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Append-only audit trail aggregate root.
 * 
 * Key invariants:
 * - Audit trail entries are append-only and immutable.
 * - Corrections create new entries referencing the original.
 * - Each entry records actor, resource, purpose, decision, and timestamp.
 */
public final class AuditTrail {
    private final List<AuditEntry> entries;

    public AuditTrail() {
        this.entries = new ArrayList<>();
    }

    /**
     * Record a new audit entry. Entries are append-only.
     */
    public AuditEntry recordEntry(
        String actorId,
        String actorDisplayName,
        String actionType,
        String resourceRef,
        String resourceDomain,
        String reasonCode,
        String correlationId,
        String resultSummary,
        String correctedEntryId,
        Instant now,
        String sourceCommandId
    ) {
        Objects.requireNonNull(now, "now is required");
        String entryId = "aud-" + UUID.randomUUID().toString();
        AuditEntry entry = new AuditEntry(
            entryId,
            requireText(actorId, "actorId"),
            requireText(actorDisplayName, "actorDisplayName"),
            requireText(actionType, "actionType"),
            requireText(resourceRef, "resourceRef"),
            requireText(resourceDomain, "resourceDomain"),
            requireText(reasonCode, "reasonCode"),
            requireText(correlationId, "correlationId"),
            resultSummary != null ? resultSummary : "",
            correctedEntryId,
            now
        );

        // Validate correction reference if provided
        if (correctedEntryId != null && !correctedEntryId.isBlank()) {
            boolean found = false;
            for (AuditEntry existing : entries) {
                if (existing.entryId().equals(correctedEntryId)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new DomainRuleViolation("corrected entry " + correctedEntryId + " not found in audit trail");
            }
        }

        entries.add(entry);
        return entry;
    }

    public List<AuditEntry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public int size() {
        return entries.size();
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
        return value;
    }
}
