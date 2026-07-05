package com.trainticket.adminaudit.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
/**
 * Event emitted when a new operator is registered.
 */
public record OperatorRegistered(
    EventEnvelope envelope,
    String operatorId,
    String email,
    String role,
    java.util.List<String> scopes
) implements AdminAuditEvent {}
