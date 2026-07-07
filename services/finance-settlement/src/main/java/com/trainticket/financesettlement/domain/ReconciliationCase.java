package com.trainticket.financesettlement.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class ReconciliationCase {
    private final String reconciliationCaseId;
    private final String orderId;
    private final String paymentIntentId;
    private final String differenceType;
    private final Money expectedAmount;
    private final Money actualAmount;
    private final String description;
    private final Instant openedAt;
    private ReconciliationCaseStatus status;
    private String resolution;
    private String resolutionNote;
    private final List<FinanceSettlementEvent> domainEvents;
    private long version;

    public enum ReconciliationCaseStatus { OPEN, INVESTIGATING, MANUAL_REVIEW, ESCALATED, RESOLVED, REJECTED }

    private ReconciliationCase(
        String reconciliationCaseId, String orderId, String paymentIntentId,
        String differenceType, Money expectedAmount, Money actualAmount,
        String description, Instant openedAt
    ) {
        this.reconciliationCaseId = requireText(reconciliationCaseId, "reconciliationCaseId");
        this.orderId = orderId != null ? orderId : "";
        this.paymentIntentId = paymentIntentId != null ? paymentIntentId : "";
        this.differenceType = requireText(differenceType, "differenceType");
        this.expectedAmount = Objects.requireNonNull(expectedAmount, "expectedAmount is required");
        this.actualAmount = Objects.requireNonNull(actualAmount, "actualAmount is required");
        this.description = requireText(description, "description");
        this.openedAt = Objects.requireNonNull(openedAt, "openedAt is required");
        this.status = ReconciliationCaseStatus.OPEN;
        this.resolution = null;
        this.resolutionNote = null;
        this.domainEvents = new ArrayList<>();
    }

    public static ReconciliationCase open(
        String orderId, String paymentIntentId, String differenceType,
        Money expectedAmount, Money actualAmount, String description,
        Instant now, String sourceCommandId, String correlationId
    ) {
        Objects.requireNonNull(expectedAmount);
        Objects.requireNonNull(actualAmount);
        if (expectedAmount.currency().equals(actualAmount.currency()) && expectedAmount.compareTo(actualAmount) == 0
            && !("missing-in-platform".equals(differenceType) || "missing-in-channel".equals(differenceType) || "refund-lag".equals(differenceType))) {
            throw new DomainRuleViolation("cannot open reconciliation case with matching amounts");
        }

        ReconciliationCase rc = new ReconciliationCase(
            UUID.randomUUID().toString(), orderId, paymentIntentId,
            differenceType, expectedAmount, actualAmount, description, now
        );

        rc.domainEvents.add(new ReconciliationCaseOpened(
            rc.reconciliationCaseId, orderId, paymentIntentId, differenceType,
            expectedAmount, actualAmount, description,
            EventMetadata.create(now, sourceCommandId, sourceCommandId, correlationId,
                Map.of("reconciliationCaseId", rc.reconciliationCaseId,
                       "differenceType", differenceType, "status", rc.status.name()))
        ));
        return rc;
    }

    public static ReconciliationCase rehydrate(
        String reconciliationCaseId, String orderId, String paymentIntentId, String differenceType,
        Money expectedAmount, Money actualAmount, String description, Instant openedAt,
        ReconciliationCaseStatus status, String resolution, String resolutionNote
    ) {
        ReconciliationCase reconciliationCase = new ReconciliationCase(reconciliationCaseId, orderId, paymentIntentId,
            differenceType, expectedAmount, actualAmount, description, openedAt);
        reconciliationCase.status = Objects.requireNonNull(status, "status is required");
        reconciliationCase.resolution = resolution;
        reconciliationCase.resolutionNote = resolutionNote;
        return reconciliationCase;
    }

    public ReconciliationCase withVersion(long version) {
        if (version < 0) {
            throw new DomainRuleViolation("version must not be negative");
        }
        this.version = version;
        return this;
    }

    public void markInvestigating(Instant now, String sourceCommandId, String causationId, String correlationId) {
        requireStatus(ReconciliationCaseStatus.OPEN);
        status = ReconciliationCaseStatus.INVESTIGATING;
    }

    public void escalate(String reason, Instant now, String sourceCommandId, String causationId, String correlationId) {
        if (status == ReconciliationCaseStatus.RESOLVED || status == ReconciliationCaseStatus.REJECTED)
            throw new DomainRuleViolation("cannot escalate already resolved/rejected case");
        status = ReconciliationCaseStatus.ESCALATED;
    }

    public void resolve(String resolution, String note, Instant now, String sourceCommandId, String causationId, String correlationId) {
        if (status == ReconciliationCaseStatus.RESOLVED) return;
        if (status == ReconciliationCaseStatus.REJECTED)
            throw new DomainRuleViolation("cannot resolve a rejected case");
        this.resolution = requireText(resolution, "resolution");
        this.resolutionNote = requireText(note, "note");
        this.status = ReconciliationCaseStatus.RESOLVED;
        domainEvents.add(new ReconciliationCaseResolved(
            reconciliationCaseId, resolution, resolutionNote,
            EventMetadata.create(now, sourceCommandId, causationId, correlationId,
                Map.of("reconciliationCaseId", reconciliationCaseId, "status", status.name()))
        ));
    }

    public void reject(String reason, Instant now, String sourceCommandId, String causationId, String correlationId) {
        if (status == ReconciliationCaseStatus.RESOLVED)
            throw new DomainRuleViolation("cannot reject an already resolved case");
        if (status == ReconciliationCaseStatus.REJECTED) return;
        this.resolution = "rejected";
        this.resolutionNote = requireText(reason, "reason");
        this.status = ReconciliationCaseStatus.REJECTED;
        domainEvents.add(new ReconciliationCaseResolved(
            reconciliationCaseId, "rejected", reason,
            EventMetadata.create(now, sourceCommandId, causationId, correlationId,
                Map.of("reconciliationCaseId", reconciliationCaseId, "status", status.name()))
        ));
    }

    public String reconciliationCaseId() { return reconciliationCaseId; }
    public String orderId() { return orderId; }
    public String paymentIntentId() { return paymentIntentId; }
    public String differenceType() { return differenceType; }
    public Money expectedAmount() { return expectedAmount; }
    public Money actualAmount() { return actualAmount; }
    public String description() { return description; }
    public Instant openedAt() { return openedAt; }
    public ReconciliationCaseStatus status() { return status; }
    public String resolution() { return resolution; }
    public String resolutionNote() { return resolutionNote; }
    public List<FinanceSettlementEvent> domainEvents() { return Collections.unmodifiableList(domainEvents); }
    public long version() { return version; }

    private void requireStatus(ReconciliationCaseStatus expected) {
        if (status != expected)
            throw new DomainRuleViolation("expected reconciliation case status " + expected + " but was " + status);
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank())
            throw new DomainRuleViolation(name + " must not be blank");
        return value;
    }
}
