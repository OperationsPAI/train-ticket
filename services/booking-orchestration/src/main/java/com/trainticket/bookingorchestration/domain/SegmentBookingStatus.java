package com.trainticket.bookingorchestration.domain;

public enum SegmentBookingStatus {
    REQUESTED,
    HOLDING,
    CONFIRMED,
    TICKETED,
    IN_FULFILLMENT,
    COMPLETED,
    CANCEL_REQUESTED,
    CANCELLED,
    CHANGE_REQUESTED,
    CHANGED,
    FAILED
}
