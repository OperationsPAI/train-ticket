package com.trainticket.bookingorchestration.domain;

public enum BookingSagaStatus {
    PLANNED,
    RESERVING,
    AWAITING_PAYMENT,
    CONFIRMING,
    TICKETING,
    COMPLETED,
    PARTIALLY_CONFIRMED,
    COMPENSATING,
    MANUAL_REVIEW,
    FAILED
}
