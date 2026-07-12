package com.trainticket.bookingorchestration.domain;

public enum BookingSagaStatus {
    PLANNED,
    RISK_CHECKING,
    RESERVING,
    SEAT_ASSIGNING,
    AWAITING_PAYMENT,
    CONFIRMING,
    TICKETING,
    INVOICING,
    COMPLETED,
    PARTIALLY_CONFIRMED,
    COMPENSATING,
    MANUAL_REVIEW,
    FAILED
}
