package com.trainticket.journeyorder.domain;

public enum OrderLifecycleState {
    DRAFT,
    PENDING_CONFIRMATION,
    PENDING_PAYMENT,
    CONFIRMING,
    CONFIRMED,
    PARTIALLY_CONFIRMED,
    IN_TRAVEL,
    COMPLETED,
    CANCELLED,
    DISRUPTED,
    FAILED,
    POST_SALES_ADJUSTED
}
