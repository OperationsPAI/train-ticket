package com.trainticket.payment.application;

import com.trainticket.payment.domain.ChannelRef;
import com.trainticket.payment.domain.PaymentIntent;
import com.trainticket.payment.domain.Refund;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(PaymentChannelClient.class)
final class NoopPaymentChannelClient implements PaymentChannelClient {
    @Override
    public HandoffOrder handoffCapture(PaymentIntent intent, String orderIdempotencyKey, String submitIdempotencyKey, String correlationId, ChannelRef requestedRef) {
        throw new IllegalStateException("payment-channel client is not configured");
    }

    @Override
    public HandoffRefund handoffRefund(PaymentIntent intent, Refund refund, String refundIdempotencyKey, String submitIdempotencyKey, String correlationId, ChannelRef originalRoute) {
        throw new IllegalStateException("payment-channel client is not configured");
    }
}
