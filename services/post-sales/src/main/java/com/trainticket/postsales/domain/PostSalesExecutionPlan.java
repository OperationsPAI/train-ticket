package com.trainticket.postsales.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class PostSalesExecutionPlan {
    private final String caseId;
    private final int version;
    private final List<PostSalesStep> steps;
    private PostSalesStepStatus status;

    public PostSalesExecutionPlan(String caseId, int version, List<PostSalesStep> steps) {
        this.caseId = PostSalesScope.requireText(caseId, "caseId");
        if (version < 1) {
            throw new DomainRuleViolation("execution plan version must be positive");
        }
        this.version = version;
        this.steps = new ArrayList<>(Objects.requireNonNull(steps, "steps are required"));
        if (this.steps.isEmpty()) {
            throw new DomainRuleViolation("execution plan must have at least one step");
        }
        this.status = PostSalesStepStatus.PLANNED;
    }

    public String caseId() { return caseId; }
    public int version() { return version; }
    public List<PostSalesStep> steps() { return Collections.unmodifiableList(steps); }
    public PostSalesStepStatus status() { return status; }

    public void start(Instant occurredAt) {
        if (status != PostSalesStepStatus.PLANNED) {
            throw new DomainRuleViolation("execution plan can only start from PLANNED status");
        }
        status = PostSalesStepStatus.EXECUTING;
        for (PostSalesStep step : steps) {
            if (step.status() == PostSalesStepStatus.PLANNED) {
                step.markExecuting();
                break;
            }
        }
    }

    public void recordStepSucceeded(PostSalesStepType stepType, String externalRef, Instant occurredAt) {
        requireStatus(PostSalesStepStatus.EXECUTING);
        PostSalesStep step = requireStep(stepType);
        ensurePriorStepsSucceeded(stepType);
        step.succeed(externalRef, occurredAt);
        if (allStepsSucceeded()) {
            status = PostSalesStepStatus.SUCCEEDED;
        }
    }

    public void recordStepFailed(PostSalesStepType stepType, String reason, boolean manualRequired, Instant occurredAt) {
        requireStatus(PostSalesStepStatus.EXECUTING);
        PostSalesStep step = requireStep(stepType);
        step.fail(reason, manualRequired, occurredAt);
        if (!manualRequired) {
            status = PostSalesStepStatus.FAILED;
        }
    }

    public void compensate() {
        if (status != PostSalesStepStatus.FAILED && status != PostSalesStepStatus.MANUAL_REQUIRED) {
            throw new DomainRuleViolation("only failed or manual-required plan can be compensated");
        }
        status = PostSalesStepStatus.SKIPPED;
    }

    private void ensurePriorStepsSucceeded(PostSalesStepType stepType) {
        for (PostSalesStep step : steps) {
            if (step.type() == stepType) {
                return;
            }
            if (step.status() != PostSalesStepStatus.SUCCEEDED) {
                throw new DomainRuleViolation("execution plan must complete " + step.type() + " before " + stepType);
            }
        }
    }

    private boolean allStepsSucceeded() {
        return steps.stream().allMatch(step -> step.status() == PostSalesStepStatus.SUCCEEDED);
    }

    private PostSalesStep requireStep(PostSalesStepType stepType) {
        return steps.stream().filter(step -> step.type() == stepType).findFirst()
            .orElseThrow(() -> new DomainRuleViolation("unknown execution step " + stepType));
    }

    private void requireStatus(PostSalesStepStatus expected) {
        if (status != expected) {
            throw new DomainRuleViolation("expected execution plan status " + expected + " but was " + status);
        }
    }
}
