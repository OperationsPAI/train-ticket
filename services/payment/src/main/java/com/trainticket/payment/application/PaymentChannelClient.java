package com.trainticket.payment.application;

import com.trainticket.payment.domain.ChannelRef;
import com.trainticket.payment.domain.Money;
import com.trainticket.payment.domain.PaymentIntent;
import com.trainticket.payment.domain.Refund;

public interface PaymentChannelClient {
    HandoffOrder handoffCapture(PaymentIntent intent, String idempotencyKey, String correlationId, ChannelRef requestedRef);

    HandoffRefund handoffRefund(PaymentIntent intent, Refund refund, String idempotencyKey, String correlationId, ChannelRef originalRoute);

    record HandoffOrder(String channelOrderId, String status, String channelTransactionId, ChannelRef channelRef) {
    }

    record HandoffRefund(String channelRefundId, String status, String channelRefundTransactionId, ChannelRef channelRef) {
    }
}
