package com.trainticket.payment.adapters.http;

import com.trainticket.payment.domain.ChannelRef;
import com.trainticket.payment.domain.Money;
import com.trainticket.payment.domain.PaymentEvent;
import com.trainticket.payment.domain.PaymentIntent;
import com.trainticket.payment.domain.PaymentIntentCreated;
import com.trainticket.payment.domain.Refund;
import java.time.Instant;

final class PaymentHttpMapper {
    private PaymentHttpMapper() {
    }

    static Money toMoney(MoneyJson json) {
        if (json == null) {
            throw new ValidationException("amount is required");
        }
        if (json.currency() == null || json.currency().isBlank()) {
            throw new ValidationException("amount.currency is required");
        }
        if (json.minorUnits() == null) {
            throw new ValidationException("amount.minorUnits is required");
        }
        return Money.fromMinorUnits(json.minorUnits(), json.currency());
    }

    static MoneyJson money(Money money) {
        return new MoneyJson(money.currency().getCurrencyCode(), money.toMinorUnits());
    }

    static ChannelRef toChannelRef(ChannelRefJson json) {
        return json == null ? null : new ChannelRef(json.channel(), json.channelOrderId(), json.channelRefundId(), json.channelTransactionId(), json.channelRefundTransactionId(), json.channelStatementId(), json.faultSeedRef());
    }

    static ChannelRefJson fromChannelRef(ChannelRef ref) {
        return ref == null ? null : new ChannelRefJson(ref.channel(), ref.channelOrderId(), ref.channelRefundId(), ref.channelTransactionId(), ref.channelRefundTransactionId(), ref.channelStatementId(), ref.faultSeedRef());
    }

    static PaymentIntentResponse intentResponse(PaymentIntent intent) {
        return new PaymentIntentResponse(intent.paymentIntentId(), intent.businessRef(), money(intent.amount()), intent.status().name(), createdAt(intent), intent.expiresAt(), fromChannelRef(intent.channelRef()));
    }

    static PaymentIntentDetailsResponse intentDetails(PaymentIntent intent) {
        return new PaymentIntentDetailsResponse(
            intent.paymentIntentId(),
            intent.businessRef(),
            intent.purpose(),
            money(intent.amount()),
            intent.payerRef(),
            intent.status().name(),
            createdAt(intent),
            intent.expiresAt(),
            money(intent.authorizedAmount()),
            money(intent.capturedAmount()),
            money(intent.refundedAmount()),
            fromChannelRef(intent.channelRef()),
            money(intent.refundableBalance())
        );
    }

    static CancelPaymentIntentResponse cancelResponse(PaymentIntent intent) {
        return new CancelPaymentIntentResponse(intent.paymentIntentId(), intent.status().name(), lastEventAt(intent));
    }

    static CapturePaymentResponse captureResponse(PaymentIntent intent, ChannelRefJson channelRef) {
        String txn = lastChannelTransactionId(intent);
        ChannelRefJson ref = fromChannelRef(intent.channelRef());
        if (ref == null && channelRef != null) {
            ref = new ChannelRefJson(channelRef.channel(), channelRef.channelOrderId(), channelRef.channelRefundId(), txn, channelRef.channelRefundTransactionId(), channelRef.channelStatementId(), channelRef.faultSeedRef());
        }
        String status = intent.channelRef() != null && intent.status().name().equals("CREATED") ? "PENDING_CHANNEL" : intent.status().name();
        return new CapturePaymentResponse(intent.paymentIntentId(), status, money(intent.capturedAmount()), txn, ref);
    }

    static RefundResponse refundResponse(Refund refund, ChannelRefJson channelRef) {
        ChannelRefJson ref = fromChannelRef(refund.channelRef());
        if (ref == null && channelRef != null) {
            ref = new ChannelRefJson(channelRef.channel(), channelRef.channelOrderId(), refund.refundId(), channelRef.channelTransactionId(), refund.channelRefundTransactionId(), channelRef.channelStatementId(), channelRef.faultSeedRef());
        }
        return new RefundResponse(refund.refundId(), refund.paymentIntentId(), money(refund.amount()), refund.status().name(), ref);
    }

    static RefundDetailsResponse refundDetails(Refund refund) {
        return new RefundDetailsResponse(refund.refundId(), refund.paymentIntentId(), money(refund.amount()), refund.status().name(), refund.reasonCode(), refund.sourceCaseRef(), refund.channelRefundTransactionId(), fromChannelRef(refund.channelRef()));
    }

    private static Instant createdAt(PaymentIntent intent) {
        return intent.domainEvents().stream()
            .filter(PaymentIntentCreated.class::isInstance)
            .map(PaymentEvent::envelope)
            .map(com.trainticket.platformkit.messaging.EventEnvelope::occurredAt)
            .findFirst()
            .orElse(Instant.EPOCH);
    }

    private static Instant lastEventAt(PaymentIntent intent) {
        return intent.domainEvents().getLast().envelope().occurredAt();
    }

    private static String lastChannelTransactionId(PaymentIntent intent) {
        return intent.channelTransactionRefs().stream().sorted().reduce((first, second) -> second).orElse("").replaceFirst("^[^:]+:", "");
    }
}
