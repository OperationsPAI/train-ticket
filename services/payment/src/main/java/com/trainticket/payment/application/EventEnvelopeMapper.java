package com.trainticket.payment.application;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.payment.domain.ChannelCallbackReceived;
import com.trainticket.payment.domain.ChannelRef;
import com.trainticket.payment.domain.DuplicateChannelCallbackDetected;
import com.trainticket.payment.domain.LatePaymentDetected;
import com.trainticket.payment.domain.Money;
import com.trainticket.payment.domain.PaymentAuthorized;
import com.trainticket.payment.domain.PaymentCaptured;
import com.trainticket.payment.domain.PaymentEvent;
import com.trainticket.payment.domain.PaymentFailed;
import com.trainticket.payment.domain.PaymentIntentCancelled;
import com.trainticket.payment.domain.PaymentIntentCreated;
import com.trainticket.payment.domain.PaymentIntentExpired;
import com.trainticket.payment.domain.PaymentRefunded;
import com.trainticket.payment.domain.PaymentTimedOut;
import com.trainticket.payment.domain.RefundFailed;
import com.trainticket.payment.domain.RefundRequested;
import com.trainticket.payment.domain.RefundSettled;
import java.util.LinkedHashMap;
import java.util.Map;

public final class EventEnvelopeMapper {
    private EventEnvelopeMapper() {
    }

    public static EventEnvelope fromDomainEvent(PaymentEvent event) {
        com.trainticket.platformkit.messaging.EventEnvelope envelope = event.envelope();
        return new EventEnvelope(
            envelope.eventId(),
            envelope.eventType(),
            envelope.occurredAt(),
            envelope.correlationId(),
            envelope.causationId(),
            envelope.producer(),
            envelope.schemaVersion(),
            payload(event)
        );
    }

    private static Map<String, Object> payload(PaymentEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        switch (event) {
            case PaymentIntentCreated created -> {
                payload.put("paymentIntentId", created.paymentIntentId());
                payload.put("businessRef", created.businessRef());
                payload.put("purpose", created.purpose());
                payload.put("amount", money(created.amount()));
                payload.put("payerRef", created.payerRef());
                payload.put("idempotencyKey", created.idempotencyKey());
                payload.put("createdAt", created.envelope().occurredAt());
            }
            case PaymentAuthorized authorized -> {
                payload.put("paymentIntentId", authorized.paymentIntentId());
                payload.put("authorizedAmount", money(authorized.authorizedAmount()));
                payload.put("channel", authorized.channel());
                payload.put("channelTransactionId", authorized.channelTransactionId());
            }
            case PaymentCaptured captured -> {
                payload.put("paymentIntentId", captured.paymentIntentId());
                payload.put("intentId", captured.paymentIntentId());
                payload.put("businessRef", captured.businessRef());
                payload.put("orderId", captured.businessRef());
                payload.put("capturedAmount", money(captured.capturedAmount()));
                payload.put("amountMinor", captured.capturedAmount().toMinorUnits());
                payload.put("capturedAt", captured.envelope().occurredAt());
                payload.put("channel", captured.channel());
                payload.put("channelTransactionId", captured.channelTransactionId());
                if (captured.channelRef() != null) {
                    payload.put("channelRef", channelRef(captured.channelRef()));
                }
            }
            case PaymentFailed failed -> {
                payload.put("paymentIntentId", failed.paymentIntentId());
                payload.put("intentId", failed.paymentIntentId());
                payload.put("businessRef", failed.businessRef());
                payload.put("orderId", failed.businessRef());
                payload.put("reasonCode", failed.reasonCode());
                payload.put("reason", failed.reasonCode());
                payload.put("retryable", failed.retryable());
                if (failed.channelRef() != null) {
                    payload.put("channelRef", channelRef(failed.channelRef()));
                }
            }
            case PaymentIntentCancelled cancelled -> {
                payload.put("paymentIntentId", cancelled.paymentIntentId());
                payload.put("reason", cancelled.reason());
            }
            case PaymentIntentExpired expired -> payload.put("paymentIntentId", expired.paymentIntentId());
            case PaymentTimedOut timedOut -> {
                payload.put("paymentIntentId", timedOut.paymentIntentId());
                payload.put("intentId", timedOut.paymentIntentId());
                payload.put("businessRef", timedOut.businessRef());
                payload.put("orderId", timedOut.businessRef());
                payload.put("expiresAt", timedOut.expiresAt());
                payload.put("reason", timedOut.reason());
            }
            case RefundRequested requested -> {
                payload.put("refundId", requested.refundId());
                payload.put("paymentIntentId", requested.paymentIntentId());
                payload.put("amount", money(requested.amount()));
                payload.put("businessCaseRef", requested.sourceCaseRef());
                payload.put("reason", requested.reasonCode());
                payload.put("idempotencyKey", requested.idempotencyKey());
            }
            case PaymentRefunded refunded -> {
                payload.put("paymentIntentId", refunded.paymentIntentId());
                payload.put("intentId", refunded.paymentIntentId());
                payload.put("businessRef", refunded.businessRef());
                payload.put("orderId", refunded.businessRef());
                payload.put("refundId", refunded.refundId());
                payload.put("amount", money(refunded.amount()));
                payload.put("amountMinor", refunded.amount().toMinorUnits());
                if (refunded.channelRef() != null) {
                    payload.put("channelRef", channelRef(refunded.channelRef()));
                }
            }
            case RefundSettled settled -> {
                payload.put("refundId", settled.refundId());
                payload.put("paymentIntentId", settled.paymentIntentId());
                payload.put("intentId", settled.paymentIntentId());
                payload.put("businessRef", settled.businessRef());
                payload.put("orderId", settled.businessRef());
                payload.put("amount", money(settled.amount()));
                payload.put("amountMinor", settled.amount().toMinorUnits());
                payload.put("channelRefundTransactionId", settled.channelRefundTransactionId());
                if (settled.channelRef() != null) {
                    payload.put("channelRef", channelRef(settled.channelRef()));
                }
            }
            case RefundFailed failed -> {
                payload.put("refundId", failed.refundId());
                payload.put("paymentIntentId", failed.paymentIntentId());
                payload.put("reason", failed.reason());
            }
            case ChannelCallbackReceived received -> {
                payload.put("callbackRecordId", received.callbackRecordId());
                payload.put("channel", received.channel());
                payload.put("callbackId", received.callbackId());
                payload.put("payloadDigest", received.payloadDigest());
                payload.put("processingStatus", received.processingStatus().name());
            }
            case DuplicateChannelCallbackDetected duplicate -> {
                payload.put("callbackRecordId", duplicate.callbackRecordId());
                payload.put("channel", duplicate.channel());
                payload.put("callbackId", duplicate.callbackId());
                payload.put("firstCallbackRecordId", duplicate.firstCallbackRecordId());
            }
            case LatePaymentDetected late -> {
                payload.put("latePaymentCaseId", late.latePaymentCaseId());
                payload.put("paymentIntentId", late.paymentIntentId());
                payload.put("capturedAmount", money(late.capturedAmount()));
                payload.put("channel", late.channel());
                payload.put("channelTransactionId", late.channelTransactionId());
            }
        }
        return payload;
    }

    public static Map<String, Object> money(Money money) {
        return Map.of("currency", money.currency().getCurrencyCode(), "minorUnits", money.toMinorUnits());
    }

    public static Map<String, Object> channelRef(ChannelRef ref) {
        Map<String, Object> value = new LinkedHashMap<>();
        putIfPresent(value, "channel", ref.channel());
        putIfPresent(value, "channelOrderId", ref.channelOrderId());
        putIfPresent(value, "channelRefundId", ref.channelRefundId());
        putIfPresent(value, "channelTransactionId", ref.channelTransactionId());
        putIfPresent(value, "channelRefundTransactionId", ref.channelRefundTransactionId());
        putIfPresent(value, "channelStatementId", ref.channelStatementId());
        putIfPresent(value, "faultSeedRef", ref.faultSeedRef());
        return value;
    }

    private static void putIfPresent(Map<String, Object> value, String key, String field) {
        if (field != null && !field.isBlank()) {
            value.put(key, field);
        }
    }
}
