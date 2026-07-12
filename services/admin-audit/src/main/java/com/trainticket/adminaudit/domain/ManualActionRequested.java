package com.trainticket.adminaudit.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
/**
 * Event emitted when a manual action is requested.
 */
public record ManualActionRequested(
    EventEnvelope envelope,
    String manualActionId,
    String targetDomain,
    String targetCommand,
    String businessRef,
    String reasonCode,
    String description,
    String requestedByOperatorId,
    String requestedByDisplayName,
    boolean requiresApproval
) implements AdminAuditEvent {}
