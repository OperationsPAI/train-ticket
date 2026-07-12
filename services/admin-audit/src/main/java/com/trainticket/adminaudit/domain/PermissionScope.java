package com.trainticket.adminaudit.domain;

/**
 * Operator permission scope for ABAC-style access control.
 */
public enum PermissionScope {
    ORDER_READ,
    ORDER_WRITE,
    REFUND_READ,
    REFUND_WRITE,
    INVENTORY_READ,
    INVENTORY_WRITE,
    PRICE_READ,
    PRICE_WRITE,
    POLICY_READ,
    POLICY_WRITE,
    OPERATOR_READ,
    OPERATOR_WRITE,
    AUDIT_READ,
    AUDIT_EXPORT,
    SENSITIVE_DATA_READ,
    CONFIG_READ,
    CONFIG_WRITE
}
