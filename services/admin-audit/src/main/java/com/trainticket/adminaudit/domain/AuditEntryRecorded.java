package com.trainticket.adminaudit.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
/**
 * Event emitted when an audit trail entry is recorded.
 */
public record AuditEntryRecorded(
    EventEnvelope envelope,
    String entryId,
    String actorId,
    String actorDisplayName,
    String actionType,
    String resourceRef,
    String resourceDomain,
    String reasonCode,
    String correlationId,
    String resultSummary,
    String correctedEntryId
) implements AdminAuditEvent {}
