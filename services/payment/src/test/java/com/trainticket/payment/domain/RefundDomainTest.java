package com.trainticket.payment.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RefundDomainTest {
    private static final Instant NOW = Instant.parse("2026-07-03T10:00:00Z");

    @Test
    void requestsAndSettlesOriginalRouteRefundAgainstCapturedBalance() {
        PaymentIntent intent = capturedIntent();

        Refund refund = Refund.request(intent, Money.of("45.00", "USD"), "post-sales-case-1", "TICKET_REFUND", "refund-idem-1", NOW.plusSeconds(30), "RequestRefund", "corr-refund");
        refund.submitToChannel("refund-txn-1");
        refund.settle(intent, "refund-txn-1", NOW.plusSeconds(40), "SettleRefund", "channel-refund-callback", "corr-refund");

        assertEquals(RefundStatus.SETTLED, refund.status());
        assertEquals(Money.of("45.00", "USD"), intent.refundedAmount());
        assertEquals(Money.of("75.00", "USD"), intent.refundableBalance());
        assertEquals(1, intent.refundHistory().size());
        assertEquals("ALIPAY", refund.channelRef().channel());
        assertInstanceOf(RefundRequested.class, refund.domainEvents().getFirst());
        RefundSettled settled = assertInstanceOf(RefundSettled.class, refund.domainEvents().get(1));
        assertEquals("RefundSettled", settled.envelope().eventType());
        assertEquals(intent.paymentIntentId(), settled.paymentIntentId());
        PaymentRefunded refunded = assertInstanceOf(PaymentRefunded.class, refund.domainEvents().get(2));
        assertEquals(refund.refundId(), refunded.refundId());
    }

    @Test
    void rejectsRefundsAboveCapturedAndNotRefundedBalance() {
        PaymentIntent intent = capturedIntent();
        Refund first = Refund.request(intent, Money.of("100.00", "USD"), "post-sales-case-1", "TICKET_REFUND", "refund-idem-1", NOW.plusSeconds(30), "RequestRefund", "corr-refund");
        first.settle(intent, "refund-txn-1", NOW.plusSeconds(40), "SettleRefund", "channel-refund-callback", "corr-refund");

        DomainRuleViolation violation = assertThrows(DomainRuleViolation.class,
            () -> Refund.request(intent, Money.of("25.00", "USD"), "post-sales-case-2", "TICKET_REFUND", "refund-idem-2", NOW.plusSeconds(50), "RequestRefund", "corr-refund"));
        assertTrue(violation.getMessage().contains("captured-and-not-refunded"));
    }

    @Test
    void recordsRetryableAndFinalRefundFailureFacts() {
        PaymentIntent intent = capturedIntent();
        Refund refund = Refund.request(intent, Money.of("20.00", "USD"), "disruption-case-1", "DISRUPTION_REFUND", "refund-idem-3", NOW.plusSeconds(30), "RequestRefund", "corr-refund");
        refund.submitToChannel("refund-txn-2");

        refund.fail("CHANNEL_TIMEOUT", true, NOW.plusSeconds(35), "FailRefund", "channel-refund-callback", "corr-refund");
        assertEquals(RefundStatus.FAILED, refund.status());
        assertInstanceOf(RefundFailed.class, refund.domainEvents().get(1));

        refund.submitToChannel("refund-txn-3");
        refund.fail("ACCOUNT_CLOSED", false, NOW.plusSeconds(45), "FailRefund", "channel-refund-callback", "corr-refund");
        assertEquals(RefundStatus.MANUAL_REVIEW_REQUIRED, refund.status());
        RefundFailed finalFailure = assertInstanceOf(RefundFailed.class, refund.domainEvents().get(2));
        assertEquals("ACCOUNT_CLOSED", finalFailure.reason());
    }

    @Test
    void allowsMultiplePartialRefundsUntilFullyRefunded() {
        PaymentIntent intent = capturedIntent();

        Refund first = Refund.request(intent, Money.of("60.00", "USD"), "post-sales-case-50", "TICKET_REFUND", "refund-half", NOW.plusSeconds(30), "RequestRefund", "corr-refund");
        first.settle(intent, "refund-half-txn", NOW.plusSeconds(40), "SettleRefund", "channel-refund-callback", "corr-refund");
        assertEquals(Money.of("60.00", "USD"), intent.refundableBalance());

        Refund second = Refund.request(intent, Money.of("60.00", "USD"), "post-sales-case-rest", "TICKET_REFUND", "refund-rest", NOW.plusSeconds(50), "RequestRefund", "corr-refund");
        second.settle(intent, "refund-rest-txn", NOW.plusSeconds(60), "SettleRefund", "channel-refund-callback", "corr-refund");

        assertEquals(Money.of("0.00", "USD"), intent.refundableBalance());
        assertEquals(2, intent.refundHistory().size());
    }

    private static PaymentIntent capturedIntent() {
        PaymentIntent intent = PaymentIntent.create(
            "order-456",
            "initial-booking",
            Money.of("120.00", "USD"),
            "payer-10",
            NOW.plusSeconds(900),
            "pay-idem-1",
            NOW,
            "CreatePaymentIntent",
            "corr-refund"
        );
        intent.capture(Money.of("120.00", "USD"), "ALIPAY", "capture-refund-base", NOW.plusSeconds(20), "CapturePayment", "channel-callback", "corr-refund");
        return intent;
    }
}
