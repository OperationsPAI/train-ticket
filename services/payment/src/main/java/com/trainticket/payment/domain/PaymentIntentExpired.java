package com.trainticket.payment.domain;

public record PaymentIntentExpired(
    EventEnvelope envelope,
    String paymentIntentId
) implements PaymentEvent {}