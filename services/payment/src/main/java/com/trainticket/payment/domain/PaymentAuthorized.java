package com.trainticket.payment.domain;

public record PaymentAuthorized(
    String paymentIntentId,
    Money authorizedAmount,
    String channel,
    String channelTransactionId,
    EventMetadata metadata
) implements PaymentEvent {
    @Override
    public String eventType() {
        return "PaymentAuthorized";
    }
}
