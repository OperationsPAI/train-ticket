package com.trainticket.adminaudit.domain;

/**
 * Lifecycle state for a ManualAction.
 */
public enum ManualActionState {
    DRAFT,
    REQUESTED,
    APPROVED,
    REJECTED,
    EXECUTED,
    FAILED
}
