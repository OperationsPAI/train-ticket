package com.trainticket.payment.domain;

public enum PaymentIntentStatus {
    CREATED,
    AUTHORIZED,
    CAPTURED,
    FAILED,
    CANCELLED,
    EXPIRED
}
