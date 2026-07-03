package com.trainticket.payment.domain;

public record RefundFailed(
    String refundId,
    String paymentIntentId,
    String reasonCode,
    boolean retryable,
    EventMetadata metadata
) implements PaymentEvent {
    @Override
    public String eventType() {
        return "RefundFailed";
    }
}
