package com.trainticket.adminaudit.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
/**
 * Event emitted when a manual action is rejected.
 */
public record ManualActionRejected(
    EventEnvelope envelope,
    String manualActionId,
    String targetDomain,
    String targetCommand,
    String businessRef,
    String rejectedByOperatorId,
    String reason
) implements AdminAuditEvent {}
