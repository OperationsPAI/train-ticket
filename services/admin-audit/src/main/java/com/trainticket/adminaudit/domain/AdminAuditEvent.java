package com.trainticket.adminaudit.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
/**
 * Sealed interface for all Admin & Audit domain events.
 */
public sealed interface AdminAuditEvent permits
    OperatorRegistered,
    ManualActionRequested,
    ManualActionApproved,
    ManualActionRejected,
    ManualActionExecuted,
    AuditEntryRecorded {
    EventEnvelope envelope();
}
