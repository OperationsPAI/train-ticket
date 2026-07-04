package com.trainticket.payment.domain;

public record LatePaymentDetected(
    EventEnvelope envelope,
    String latePaymentCaseId,
    String paymentIntentId,
    Money capturedAmount,
    String channel,
    String channelTransactionId
) implements PaymentEvent {}