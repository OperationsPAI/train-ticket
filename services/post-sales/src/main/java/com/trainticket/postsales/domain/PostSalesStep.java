package com.trainticket.postsales.domain;

import java.time.Instant;
import java.util.Objects;

public final class PostSalesStep {
    private final PostSalesStepType type;
    private final String targetContext;
    private final String idempotencyKey;
    private final int maxRetries;
    private PostSalesStepStatus status;
    private String externalRef;
    private String failureReason;
    private Instant completedAt;

    public PostSalesStep(PostSalesStepType type, String targetContext, String idempotencyKey, int maxRetries) {
        this.type = Objects.requireNonNull(type, "type is required");
        this.targetContext = PostSalesScope.requireText(targetContext, "targetContext");
        this.idempotencyKey = PostSalesScope.requireText(idempotencyKey, "idempotencyKey");
        if (maxRetries < 0) {
            throw new DomainRuleViolation("maxRetries must not be negative");
        }
        this.maxRetries = maxRetries;
        this.status = PostSalesStepStatus.PLANNED;
    }


    public static PostSalesStep rehydrate(
        PostSalesStepType type,
        String targetContext,
        String idempotencyKey,
        int maxRetries,
        PostSalesStepStatus status,
        String externalRef,
        String failureReason,
        Instant completedAt
    ) {
        PostSalesStep step = new PostSalesStep(type, targetContext, idempotencyKey, maxRetries);
        step.status = Objects.requireNonNull(status, "status is required");
        step.externalRef = externalRef;
        step.failureReason = failureReason;
        step.completedAt = completedAt;
        return step;
    }

    public PostSalesStepType type() { return type; }
    public String targetContext() { return targetContext; }
    public String idempotencyKey() { return idempotencyKey; }
    public int maxRetries() { return maxRetries; }
    public PostSalesStepStatus status() { return status; }
    public String externalRef() { return externalRef; }
    public String failureReason() { return failureReason; }
    public Instant completedAt() { return completedAt; }

    public void markExecuting() {
        if (status != PostSalesStepStatus.PLANNED) {
            throw new DomainRuleViolation("only planned post-sales steps may start executing");
        }
        status = PostSalesStepStatus.EXECUTING;
    }

    public void succeed(String externalRef, Instant occurredAt) {
        if (status == PostSalesStepStatus.SUCCEEDED) {
            return;
        }
        if (status != PostSalesStepStatus.PLANNED && status != PostSalesStepStatus.EXECUTING) {
            throw new DomainRuleViolation("post-sales step cannot succeed from status " + status);
        }
        this.status = PostSalesStepStatus.SUCCEEDED;
        this.externalRef = PostSalesScope.requireText(externalRef, "externalRef");
        this.completedAt = Objects.requireNonNull(occurredAt, "occurredAt is required");
    }

    public void fail(String reason, boolean manualRequired, Instant occurredAt) {
        if (status == PostSalesStepStatus.SUCCEEDED) {
            throw new DomainRuleViolation("succeeded post-sales step cannot be failed");
        }
        this.status = manualRequired ? PostSalesStepStatus.MANUAL_REQUIRED : PostSalesStepStatus.FAILED;
        this.failureReason = PostSalesScope.requireText(reason, "reason");
        this.completedAt = Objects.requireNonNull(occurredAt, "occurredAt is required");
    }

    public PostSalesStep copy() {
        PostSalesStep copy = new PostSalesStep(type, targetContext, idempotencyKey, maxRetries);
        copy.status = status;
        copy.externalRef = externalRef;
        copy.failureReason = failureReason;
        copy.completedAt = completedAt;
        return copy;
    }
}
