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
        ChannelRef ref = requestedRef == null ? new ChannelRef("ALIPAY", null, null, null, null, null, null) : requestedRef;
        String transactionId = "txn-" + stripCommandPrefix(submitIdempotencyKey);
        ChannelRef completed = new ChannelRef(ref.channel(), "co-" + stripCommandPrefix(orderIdempotencyKey), null, transactionId, null, null, ref.faultSeedRef());
        return new HandoffOrder(completed.channelOrderId(), "SUCCEEDED", completed.channelTransactionId(), completed);
    }

    @Override
    public HandoffRefund handoffRefund(PaymentIntent intent, Refund refund, String refundIdempotencyKey, String submitIdempotencyKey, String correlationId, ChannelRef originalRoute) {
        ChannelRef ref = originalRoute == null ? intent.channelRef() : originalRoute;
        String refundTransactionId = "rftxn-" + stripCommandPrefix(submitIdempotencyKey);
        ChannelRef completed = new ChannelRef(ref.channel(), ref.channelOrderId(), "cr-" + stripCommandPrefix(refundIdempotencyKey), ref.channelTransactionId(), refundTransactionId, null, ref.faultSeedRef());
        return new HandoffRefund(completed.channelRefundId(), "SUCCEEDED", completed.channelRefundTransactionId(), completed);
    }

    private static String stripCommandPrefix(String value) {
        if (value == null || value.isBlank()) {
            return java.util.UUID.randomUUID().toString();
        }
        String trimmed = value.trim();
        return trimmed.startsWith("cmd-") ? trimmed.substring(4) : trimmed;
    }
}
