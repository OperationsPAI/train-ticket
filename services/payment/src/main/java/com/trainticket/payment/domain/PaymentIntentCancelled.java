package com.trainticket.payment.domain;

public record PaymentIntentCancelled(
    EventEnvelope envelope,
    String paymentIntentId,
    String reason
) implements PaymentEvent {}