package com.trainticket.adminaudit.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
/**
 * Event emitted when a manual action is approved (four-eyes).
 */
public record ManualActionApproved(
    EventEnvelope envelope,
    String manualActionId,
    String targetDomain,
    String targetCommand,
    String businessRef,
    String approvedByOperatorId,
    String approvedByDisplayName
) implements AdminAuditEvent {}
