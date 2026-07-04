package com.trainticket.adminaudit.domain;

/**
 * Event emitted when a manual action is executed or fails.
 */
public record ManualActionExecuted(
    EventEnvelope envelope,
    String manualActionId,
    String targetDomain,
    String targetCommand,
    String businessRef,
    String resultSummary
) implements AdminAuditEvent {}
