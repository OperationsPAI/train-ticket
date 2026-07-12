package com.trainticket.adminaudit.domain;

/**
 * Approval record captured within ManualAction for four-eyes validation.
 * This records who approved/rejected and when.
 */
public record Approval(
    String approvedByOperatorId,
    String approvedByDisplayName,
    java.time.Instant approvedAt,
    boolean granted,
    String rejectionReason
) {}
