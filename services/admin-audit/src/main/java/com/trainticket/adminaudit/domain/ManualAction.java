package com.trainticket.adminaudit.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.platformkit.messaging.EnvelopeFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root for a controlled manual action.
 * 
 * Key invariants:
 * - Manual actions target a domain command, never direct state writes.
 * - The target domain may reject the command and the rejection is audited.
 * - High-risk manual actions require an approval with a distinct approver (four-eyes).
 * - An operator cannot approve their own action.
 */
public final class ManualAction {
    private final String manualActionId;
    private final String targetDomain;
    private final String targetCommand;
    private final String businessRef;
    private final String reasonCode;
    private final String description;
    private final OperatorRef requestedBy;
    private final boolean requiresApproval;
    private final Instant requestedAt;
    private OperatorRef approvedBy;
    private Instant approvedAt;
    private String rejectionReason;
    private ManualActionState state;
    private String resultSummary;
    private final List<AdminAuditEvent> domainEvents;

    private ManualAction(
        String manualActionId,
        String targetDomain,
        String targetCommand,
        String businessRef,
        String reasonCode,
        String description,
        OperatorRef requestedBy,
        boolean requiresApproval,
        Instant requestedAt
    ) {
        this.manualActionId = requireText(manualActionId, "manualActionId");
        this.targetDomain = requireText(targetDomain, "targetDomain");
        this.targetCommand = requireText(targetCommand, "targetCommand");
        this.businessRef = requireText(businessRef, "businessRef");
        this.reasonCode = requireText(reasonCode, "reasonCode");
        this.description = requireText(description, "description");
        this.requestedBy = Objects.requireNonNull(requestedBy, "requestedBy is required");
        this.requiresApproval = requiresApproval;
        this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt is required");
        this.state = ManualActionState.DRAFT;
        this.domainEvents = new ArrayList<>();
    }

    /**
     * Create a new manual action request.
     */
    public static ManualAction request(
        String targetDomain,
        String targetCommand,
        String businessRef,
        String reasonCode,
        String description,
        OperatorRef requestedBy,
        boolean requiresApproval,
        Instant now,
        String sourceCommandId,
        String correlationId
    ) {
        String actionId = "ma-" + UUID.randomUUID().toString();
        ManualAction action = new ManualAction(
            actionId,
            targetDomain,
            targetCommand,
            businessRef,
            reasonCode,
            description,
            requestedBy,
            requiresApproval,
            now
        );
        action.state = ManualActionState.REQUESTED;
        action.domainEvents.add(new ManualActionRequested(
            EnvelopeFactory.create("ManualActionRequested", now, sourceCommandId, correlationId, "admin-audit"),
            actionId,
            targetDomain,
            targetCommand,
            businessRef,
            reasonCode,
            description,
            requestedBy.operatorId(),
            requestedBy.displayName(),
            requiresApproval
        ));
        return action;
    }

    // --- Accessors ---

    public String manualActionId() { return manualActionId; }
    public String targetDomain() { return targetDomain; }
    public String targetCommand() { return targetCommand; }
    public String businessRef() { return businessRef; }
    public String reasonCode() { return reasonCode; }
    public String description() { return description; }
    public OperatorRef requestedBy() { return requestedBy; }
    public boolean requiresApproval() { return requiresApproval; }
    public Instant requestedAt() { return requestedAt; }
    public OperatorRef approvedBy() { return approvedBy; }
    public Instant approvedAt() { return approvedAt; }
    public String rejectionReason() { return rejectionReason; }
    public ManualActionState state() { return state; }
    public String resultSummary() { return resultSummary; }
    public List<AdminAuditEvent> domainEvents() { return List.copyOf(domainEvents); }

    // --- Commands ---

    /**
     * Approve the manual action (four-eyes check).
     * The approver must be different from the requester.
     */
    public void approve(OperatorIdentity approverIdentity, OperatorRef approver, Instant now, String sourceCommandId, String correlationId) {
        requireState(ManualActionState.REQUESTED);
        Objects.requireNonNull(approverIdentity, "approverIdentity is required");
        Objects.requireNonNull(approver, "approver is required");
        Objects.requireNonNull(now, "now is required");

        approverIdentity.assertActive();
        approverIdentity.assertNotOperator(requestedBy);
        // Also check that the approver ref is not the same as the requester ref
        if (requestedBy.operatorId().equals(approver.operatorId())) {
            throw new DomainRuleViolation("approver " + approver.operatorId() + " cannot approve their own action");
        }
        if (requiresApproval) {
            // For high-risk actions, the approver must have the OPERATOR_WRITE scope
            approverIdentity.assertHasScope(PermissionScope.OPERATOR_WRITE);
        }

        this.approvedBy = approver;
        this.approvedAt = now;
        this.state = ManualActionState.APPROVED;
        this.domainEvents.add(new ManualActionApproved(
            EnvelopeFactory.create("ManualActionApproved", now, sourceCommandId, correlationId, "admin-audit"),
            manualActionId,
            targetDomain,
            targetCommand,
            businessRef,
            approver.operatorId(),
            approver.displayName()
        ));
    }

    /**
     * Reject the manual action.
     */
    public void reject(OperatorRef rejectedBy, String reason, Instant now, String sourceCommandId, String correlationId) {
        requireState(ManualActionState.REQUESTED);
        Objects.requireNonNull(rejectedBy, "rejectedBy is required");
        this.rejectionReason = requireText(reason, "reason");
        Objects.requireNonNull(now, "now is required");

        this.state = ManualActionState.REJECTED;
        this.domainEvents.add(new ManualActionRejected(
            EnvelopeFactory.create("ManualActionRejected", now, sourceCommandId, correlationId, "admin-audit"),
            manualActionId,
            targetDomain,
            targetCommand,
            businessRef,
            rejectedBy.operatorId(),
            reason
        ));
    }

    /**
     * Execute the approved manual action.
     */
    public void execute(String resultSummary, Instant now, String sourceCommandId, String correlationId) {
        requireState(ManualActionState.APPROVED);
        if (requiresApproval && approvedBy == null) {
            throw new DomainRuleViolation("manual action requires approval before execution");
        }
        this.resultSummary = requireText(resultSummary, "resultSummary");
        this.state = ManualActionState.EXECUTED;
        this.domainEvents.add(new ManualActionExecuted(
            EnvelopeFactory.create("ManualActionExecuted", now, sourceCommandId, correlationId, "admin-audit"),
            manualActionId,
            targetDomain,
            targetCommand,
            businessRef,
            resultSummary
        ));
    }

    /**
     * Record that the target domain rejected the command.
     */
    public void markFailed(String failureSummary, Instant now, String sourceCommandId, String correlationId) {
        requireState(ManualActionState.APPROVED);
        this.resultSummary = requireText(failureSummary, "failureSummary");
        this.state = ManualActionState.FAILED;
        this.domainEvents.add(new ManualActionExecuted(
            EnvelopeFactory.create("ManualActionExecuted", now, sourceCommandId, correlationId, "admin-audit"),
            manualActionId,
            targetDomain,
            targetCommand,
            businessRef,
            "FAILED: " + failureSummary
        ));
    }

    private void requireState(ManualActionState expected) {
        if (state != expected) {
            throw new DomainRuleViolation("expected manual action state " + expected + " but was " + state);
        }
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
        return value;
    }
}
