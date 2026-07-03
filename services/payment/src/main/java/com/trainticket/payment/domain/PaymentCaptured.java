package com.trainticket.payment.domain;

public record PaymentCaptured(
    String paymentIntentId,
    Money capturedAmount,
    String channel,
    String channelTransactionId,
    EventMetadata metadata
) implements PaymentEvent {
    @Override
    public String eventType() {
        return "PaymentCaptured";
    }
}
