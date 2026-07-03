package com.trainticket.postsales.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class PostSalesCase {
    private final String caseId;
    private final String journeyOrderId;
    private final PostSalesCaseType caseType;
    private final PostSalesScope scope;
    private final String reasonCode;
    private final String actorRef;
    private final String idempotencyKey;
    private final List<PostSalesStep> executionPlan;
    private final List<PostSalesEvent> domainEvents;
    private PostSalesCaseStatus status;
    private PostSalesDecision decision;
    private String terminalReason;

    private PostSalesCase(
        String caseId,
        String journeyOrderId,
        PostSalesCaseType caseType,
        PostSalesScope scope,
        String reasonCode,
        String actorRef,
        String idempotencyKey
    ) {
        this.caseId = PostSalesScope.requireText(caseId, "caseId");
        this.journeyOrderId = PostSalesScope.requireText(journeyOrderId, "journeyOrderId");
        this.caseType = Objects.requireNonNull(caseType, "caseType is required");
        this.scope = Objects.requireNonNull(scope, "scope is required");
        this.reasonCode = PostSalesScope.requireText(reasonCode, "reasonCode");
        this.actorRef = PostSalesScope.requireText(actorRef, "actorRef");
        this.idempotencyKey = PostSalesScope.requireText(idempotencyKey, "idempotencyKey");
        this.executionPlan = new ArrayList<>();
        this.domainEvents = new ArrayList<>();
        this.status = PostSalesCaseStatus.OPENED;
    }

    public static PostSalesCase request(
        String journeyOrderId,
        PostSalesCaseType caseType,
        PostSalesScope scope,
        String reasonCode,
        String actorRef,
        String idempotencyKey,
        Instant occurredAt,
        String sourceCommandId,
        String correlationId
    ) {
        PostSalesCase postSalesCase = new PostSalesCase(UUID.randomUUID().toString(), journeyOrderId, caseType, scope, reasonCode, actorRef, idempotencyKey);
        postSalesCase.domainEvents.add(new PostSalesRequested(postSalesCase.caseId, journeyOrderId, caseType, scope, reasonCode,
            metadata(occurredAt, sourceCommandId, sourceCommandId, correlationId, postSalesCase.status)));
        return postSalesCase;
    }

    public String caseId() { return caseId; }
    public String journeyOrderId() { return journeyOrderId; }
    public PostSalesCaseType caseType() { return caseType; }
    public PostSalesScope scope() { return scope; }
    public String reasonCode() { return reasonCode; }
    public String actorRef() { return actorRef; }
    public String idempotencyKey() { return idempotencyKey; }
    public PostSalesCaseStatus status() { return status; }
    public PostSalesDecision decision() { return decision; }
    public String terminalReason() { return terminalReason; }
    public List<PostSalesStep> executionPlan() { return executionPlan.stream().map(PostSalesStep::copy).toList(); }
    public List<PostSalesEvent> domainEvents() { return List.copyOf(domainEvents); }

    public void beginEvaluation(Instant occurredAt, String sourceCommandId, String causationId, String correlationId) {
        requireStatus(PostSalesCaseStatus.OPENED);
        status = PostSalesCaseStatus.ELIGIBILITY_CHECKING;
    }

    public void recordDecision(PostSalesDecision decision, boolean requireUserConfirmation, boolean requireManualApproval, Instant occurredAt, String sourceCommandId, String causationId, String correlationId) {
        requireStatus(PostSalesCaseStatus.ELIGIBILITY_CHECKING);
        if (!caseId.equals(Objects.requireNonNull(decision, "decision is required").caseId())) {
            throw new DomainRuleViolation("decision must belong to this post-sales case");
        }
        if (!decision.kind().name().equals(caseType.name()) && !(caseType == PostSalesCaseType.CANCELLATION && decision.kind() == DecisionKind.REFUND)) {
            throw new DomainRuleViolation("decision kind must match post-sales case type");
        }
        this.decision = decision;
        domainEvents.add(new PostSalesEvaluated(caseId, decision.kind(), decision.eligible(), decision.ruleSnapshot().farePricingEvaluationRef(),
            metadata(occurredAt, sourceCommandId, causationId, correlationId, PostSalesCaseStatus.ELIGIBILITY_CHECKING)));
        if (!decision.eligible()) {
            reject(decision.reasonCode(), occurredAt, sourceCommandId, causationId, correlationId);
            return;
        }
        if (requireManualApproval) {
            status = PostSalesCaseStatus.PENDING_APPROVAL;
            domainEvents.add(new PostSalesManualReviewRequired(caseId, "manual approval required for decision " + decision.reasonCode(),
                metadata(occurredAt, sourceCommandId, causationId, correlationId, status)));
        } else if (requireUserConfirmation) {
            status = PostSalesCaseStatus.PENDING_USER_CONFIRMATION;
        } else {
            status = PostSalesCaseStatus.QUOTED;
        }
    }

    public void approve(String approvalRef, Instant occurredAt, String sourceCommandId, String causationId, String correlationId) {
        requireDecision();
        if (status != PostSalesCaseStatus.QUOTED && status != PostSalesCaseStatus.PENDING_USER_CONFIRMATION && status != PostSalesCaseStatus.PENDING_APPROVAL) {
            throw new DomainRuleViolation("post-sales case cannot be approved from status " + status);
        }
        if (!occurredAt.isBefore(decision.expiresAt())) {
            throw new DomainRuleViolation("post-sales decision quote has expired");
        }
        status = PostSalesCaseStatus.APPROVED;
        domainEvents.add(new PostSalesApproved(caseId, decision.kind(), PostSalesScope.requireText(approvalRef, "approvalRef"),
            metadata(occurredAt, sourceCommandId, causationId, correlationId, status)));
    }

    public void reject(String reason, Instant occurredAt, String sourceCommandId, String causationId, String correlationId) {
        if (status == PostSalesCaseStatus.EXECUTING || status == PostSalesCaseStatus.APPLIED) {
            throw new DomainRuleViolation("post-sales case cannot be rejected after execution starts");
        }
        status = PostSalesCaseStatus.REJECTED;
        terminalReason = PostSalesScope.requireText(reason, "reason");
        domainEvents.add(new PostSalesRejected(caseId, terminalReason,
            metadata(occurredAt, sourceCommandId, causationId, correlationId, status)));
    }

    public void requestExecution(Instant occurredAt, String sourceCommandId, String causationId, String correlationId) {
        requireStatus(PostSalesCaseStatus.APPROVED);
        buildExecutionPlan();
        status = PostSalesCaseStatus.EXECUTING;
        domainEvents.add(new PostSalesExecutionRequested(caseId, executionPlan.stream().map(PostSalesStep::type).toList(),
            metadata(occurredAt, sourceCommandId, causationId, correlationId, status)));
    }

    public void recordStepSucceeded(PostSalesStepType stepType, String externalRef, Instant occurredAt, String sourceCommandId, String causationId, String correlationId) {
        requireStatus(PostSalesCaseStatus.EXECUTING);
        PostSalesStep step = requireStep(stepType);
        ensurePriorStepsSucceeded(stepType);
        step.succeed(externalRef, occurredAt);
        domainEvents.add(new PostSalesStepSucceeded(caseId, stepType, externalRef, metadata(occurredAt, sourceCommandId, causationId, correlationId, status)));
        if (allStepsSucceeded()) {
            apply("all post-sales execution steps succeeded", occurredAt, sourceCommandId, causationId, correlationId);
        }
    }

    public void recordStepFailed(PostSalesStepType stepType, String reason, boolean recoverable, Instant occurredAt, String sourceCommandId, String causationId, String correlationId) {
        requireStatus(PostSalesCaseStatus.EXECUTING);
        PostSalesStep step = requireStep(stepType);
        step.fail(reason, recoverable, occurredAt);
        domainEvents.add(new PostSalesStepFailed(caseId, stepType, reason, recoverable, metadata(occurredAt, sourceCommandId, causationId, correlationId, status)));
        if (recoverable) {
            status = PostSalesCaseStatus.MANUAL_REVIEW_REQUIRED;
            terminalReason = "manual review required after " + stepType + " failed: " + reason;
            domainEvents.add(new PostSalesManualReviewRequired(caseId, terminalReason, metadata(occurredAt, sourceCommandId, causationId, correlationId, status)));
        } else {
            status = PostSalesCaseStatus.FAILED;
            terminalReason = reason;
        }
    }

    public void completeManualReview(String resultSummary, Instant occurredAt, String sourceCommandId, String causationId, String correlationId) {
        requireStatus(PostSalesCaseStatus.MANUAL_REVIEW_REQUIRED);
        apply(resultSummary, occurredAt, sourceCommandId, causationId, correlationId);
    }

    private void apply(String resultSummary, Instant occurredAt, String sourceCommandId, String causationId, String correlationId) {
        status = PostSalesCaseStatus.APPLIED;
        terminalReason = PostSalesScope.requireText(resultSummary, "resultSummary");
        domainEvents.add(new PostSalesApplied(caseId, journeyOrderId, caseType, resultSummary,
            metadata(occurredAt, sourceCommandId, causationId, correlationId, status)));
    }

    private void buildExecutionPlan() {
        if (!executionPlan.isEmpty()) {
            return;
        }
        if (caseType == PostSalesCaseType.CHANGE) {
            ChangeFlowSnapshot flow = decision.changeFlowSnapshot();
            executionPlan.add(new PostSalesStep(PostSalesStepType.HOLD_REPLACEMENT_CAPACITY, flow.replacementCapacityHoldRef(), key("hold-replacement-capacity"), 2));
            if (flow.financialAction() == ChangeFinancialAction.COLLECT_DIFFERENCE_PAYMENT) {
                executionPlan.add(new PostSalesStep(PostSalesStepType.REQUEST_DIFFERENCE_PAYMENT, "payment:fare-difference", key("fare-difference-payment"), 1));
            } else if (flow.financialAction() == ChangeFinancialAction.REQUEST_ASYNC_REFUND) {
                executionPlan.add(new PostSalesStep(PostSalesStepType.REQUEST_ASYNC_REFUND, "payment:async-change-refund", key("async-change-refund"), 1));
            }
            executionPlan.add(new PostSalesStep(PostSalesStepType.VOID_OLD_ENTITLEMENT, flow.oldEntitlementVoidRef(), key("void-old-entitlement"), 2));
            executionPlan.add(new PostSalesStep(PostSalesStepType.ISSUE_NEW_ENTITLEMENT, flow.newEntitlementIssueRef(), key("issue-new-entitlement"), 2));
            executionPlan.add(new PostSalesStep(PostSalesStepType.RELEASE_OLD_CAPACITY, flow.oldCapacityReleaseRef(), key("release-old-capacity"), 2));
            executionPlan.add(new PostSalesStep(PostSalesStepType.APPLY_RESULT, journeyOrderId, key("apply-result"), 0));
            return;
        }
        executionPlan.add(new PostSalesStep(PostSalesStepType.DECISION_CONFIRMED, decision.ruleSnapshot().farePricingEvaluationRef(), key("decision"), 0));
        executionPlan.add(new PostSalesStep(PostSalesStepType.VOID_ENTITLEMENT, String.join(",", scope.entitlementRefs()), key("void-entitlement"), 2));
        executionPlan.add(new PostSalesStep(PostSalesStepType.CANCEL_SEGMENT, String.join(",", scope.segmentRefs()), key("cancel-segment"), 2));
        executionPlan.add(new PostSalesStep(PostSalesStepType.RELEASE_CAPACITY, String.join(",", scope.segmentRefs()), key("release-capacity"), 2));
        executionPlan.add(new PostSalesStep(PostSalesStepType.REQUEST_REFUND, "payment:refund", key("refund"), 1));
        executionPlan.add(new PostSalesStep(PostSalesStepType.APPLY_RESULT, journeyOrderId, key("apply-result"), 0));
    }

    private void ensurePriorStepsSucceeded(PostSalesStepType stepType) {
        for (PostSalesStep step : executionPlan) {
            if (step.type() == stepType) {
                return;
            }
            if (step.status() != PostSalesStepStatus.SUCCEEDED) {
                throw new DomainRuleViolation("post-sales flow must complete " + step.type() + " before " + stepType);
            }
        }
    }

    private boolean allStepsSucceeded() {
        return executionPlan.stream().allMatch(step -> step.status() == PostSalesStepStatus.SUCCEEDED);
    }

    private PostSalesStep requireStep(PostSalesStepType stepType) {
        return executionPlan.stream().filter(step -> step.type() == stepType).findFirst()
            .orElseThrow(() -> new DomainRuleViolation("unknown post-sales execution step " + stepType));
    }

    private String key(String purpose) {
        return "post-sales:" + caseId + ":" + purpose;
    }

    private void requireDecision() {
        if (decision == null) {
            throw new DomainRuleViolation("post-sales case requires an immutable decision snapshot first");
        }
    }

    private void requireStatus(PostSalesCaseStatus expected) {
        if (status != expected) {
            throw new DomainRuleViolation("expected post-sales case status " + expected + " but was " + status);
        }
    }

    private static EventMetadata metadata(Instant occurredAt, String sourceCommandId, String causationId, String correlationId, PostSalesCaseStatus status) {
        return EventMetadata.create(occurredAt, sourceCommandId, causationId, correlationId, Map.of("status", status.name()));
    }
}
