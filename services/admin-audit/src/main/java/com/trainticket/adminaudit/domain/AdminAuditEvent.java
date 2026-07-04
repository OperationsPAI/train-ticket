package com.trainticket.adminaudit.domain;

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
