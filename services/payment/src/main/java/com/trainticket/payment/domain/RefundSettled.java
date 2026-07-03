package com.trainticket.payment.domain;

public record RefundSettled(
    String refundId,
    String paymentIntentId,
    Money amount,
    String channelRefundTransactionId,
    EventMetadata metadata
) implements PaymentEvent {
    @Override
    public String eventType() {
        return "RefundSettled";
    }
}
