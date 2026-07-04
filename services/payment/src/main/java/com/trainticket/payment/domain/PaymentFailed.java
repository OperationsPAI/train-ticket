package com.trainticket.payment.domain;

public record PaymentFailed(
    EventEnvelope envelope,
    String paymentIntentId,
    String reasonCode,
    boolean retryable
) implements PaymentEvent {}