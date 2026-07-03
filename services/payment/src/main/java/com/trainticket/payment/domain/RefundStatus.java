package com.trainticket.payment.domain;

public enum RefundStatus {
    REQUESTED,
    SUBMITTED,
    SETTLED,
    FAILED,
    MANUAL_REVIEW_REQUIRED
}
