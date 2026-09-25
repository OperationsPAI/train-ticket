package com.trainticket.postsales.domain;

import java.util.Objects;

/**
 * Stable identity for rebuilding the execution trigger of a case whose original
 * approval events are no longer available from transport retention.
 */
public record PostSalesExecutionRedrive(
    String approvalRef,
    String executionApprovalRef,
    EventMetadata approvedMetadata,
    EventMetadata executionStartedMetadata
) {
    public PostSalesExecutionRedrive {
        approvalRef = PostSalesScope.requireText(approvalRef, "approvalRef");
        executionApprovalRef = PostSalesScope.requireText(executionApprovalRef, "executionApprovalRef");
        approvedMetadata = Objects.requireNonNull(approvedMetadata, "approvedMetadata is required");
        executionStartedMetadata = Objects.requireNonNull(executionStartedMetadata, "executionStartedMetadata is required");
    }
}
