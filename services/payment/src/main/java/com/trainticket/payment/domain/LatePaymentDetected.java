package com.trainticket.payment.domain;

import com.trainticket.platformkit.messaging.EventEnvelope;
public record LatePaymentDetected(
    EventEnvelope envelope,
    String latePaymentCaseId,
    String paymentIntentId,
    Money capturedAmount,
    String channel,
    String channelTransactionId
) implements PaymentEvent {}
