package com.trainticket.payment.adapters.http;

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

    static PaymentIntentResponse intentResponse(PaymentIntent intent) {
        return new PaymentIntentResponse(intent.paymentIntentId(), intent.businessRef(), money(intent.amount()), intent.status().name(), createdAt(intent));
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
            money(intent.refundedAmount())
        );
    }

    static CancelPaymentIntentResponse cancelResponse(PaymentIntent intent) {
        return new CancelPaymentIntentResponse(intent.paymentIntentId(), intent.status().name(), lastEventAt(intent));
    }

    static CapturePaymentResponse captureResponse(PaymentIntent intent) {
        return new CapturePaymentResponse(intent.paymentIntentId(), intent.status().name(), money(intent.capturedAmount()), lastChannelTransactionId(intent));
    }

    static RefundResponse refundResponse(Refund refund) {
        return new RefundResponse(refund.refundId(), refund.paymentIntentId(), money(refund.amount()), refund.status().name());
    }

    static RefundDetailsResponse refundDetails(Refund refund) {
        return new RefundDetailsResponse(refund.refundId(), refund.paymentIntentId(), money(refund.amount()), refund.status().name(), refund.reasonCode(), refund.sourceCaseRef(), refund.channelRefundTransactionId());
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
