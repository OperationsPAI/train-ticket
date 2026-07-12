package com.trainticket.postsales.domain;

public enum PostSalesCaseStatus {
    OPENED,
    ELIGIBILITY_CHECKING,
    QUOTED,
    PENDING_USER_CONFIRMATION,
    PENDING_APPROVAL,
    APPROVED,
    EXECUTING,
    COMPENSATION_PENDING,
    APPLIED,
    REJECTED,
    FAILED,
    CANCELLED,
    MANUAL_REVIEW_REQUIRED
}
