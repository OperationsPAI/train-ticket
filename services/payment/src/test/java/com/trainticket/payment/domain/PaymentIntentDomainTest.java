package com.trainticket.payment.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaymentIntentDomainTest {
    private static final Instant NOW = Instant.parse("2026-07-03T10:00:00Z");

    @Test
    void createsPaymentIntentAndReusesBusinessIdempotencyKey() {
        IdempotencyLedger<PaymentIntent> ledger = new IdempotencyLedger<>();

        PaymentIntent first = ledger.recordOrReturn("order-123:initial-booking", "idem-1", NOW,
            () -> newIntent("idem-1"));
        PaymentIntent second = ledger.recordOrReturn("order-123:initial-booking", "idem-1", NOW.plusSeconds(1),
            () -> newIntent("idem-1"));

        assertSame(first, second);
        assertEquals(1, ledger.size());
        assertEquals(PaymentIntentStatus.CREATED, first.status());
        assertEquals("idem-1", first.idempotencyKey());
        assertInstanceOf(PaymentIntentCreated.class, first.domainEvents().getFirst());
        assertTrue(first.semanticallyMatches("order-123", "initial-booking", Money.of("120.00", "USD"), "payer-9", NOW.plusSeconds(900), "idem-1"));
    }

    @Test
    void authorizesAndCapturesWithoutWritingOtherDomains() {
        PaymentIntent intent = newIntent("idem-capture");

        intent.authorize(Money.of("120.00", "USD"), "stripe", "auth-1", NOW.plusSeconds(10), "AuthorizePayment", "callback-1", "corr-1");
        intent.capture(Money.of("120.00", "USD"), "stripe", "capture-1", NOW.plusSeconds(20), "CapturePayment", "callback-2", "corr-1");

        assertEquals(PaymentIntentStatus.CAPTURED, intent.status());
        assertEquals(Money.of("120.00", "USD"), intent.capturedAmount());
        assertEquals(3, intent.domainEvents().size());
        assertInstanceOf(PaymentAuthorized.class, intent.domainEvents().get(1));
        PaymentCaptured captured = assertInstanceOf(PaymentCaptured.class, intent.domainEvents().get(2));
        assertEquals("PaymentCaptured", captured.envelope().eventType());
        assertEquals("capture-1", captured.channelTransactionId());
        assertFalse(captured.envelope().eventType().isEmpty(), "payment facts must carry an event type");
    }

    @Test
    void preventsExpiredOrTerminalPaymentActivity() {
        PaymentIntent intent = newIntent("idem-expire");
        intent.expire(NOW.plusSeconds(901), "ExpirePaymentIntent", "timer", "corr-2");

        assertEquals(PaymentIntentStatus.EXPIRED, intent.status());
        assertInstanceOf(PaymentIntentExpired.class, intent.domainEvents().get(1));
        PaymentTimedOut timedOut = assertInstanceOf(PaymentTimedOut.class, intent.domainEvents().get(2));
        assertEquals("TIMEOUT", timedOut.reason());
        assertThrows(DomainRuleViolation.class,
            () -> intent.capture(Money.of("120.00", "USD"), "stripe", "capture-late", NOW.plusSeconds(902), "CapturePayment", "callback", "corr-2"));
    }

    @Test
    void lateCaptureAfterCancellationOpensLatePaymentCaseOnly() {
        PaymentIntent intent = newIntent("idem-cancel");
        intent.cancel("order cancelled upstream", NOW.plusSeconds(20), "CancelPaymentIntent", "order-cancelled", "corr-3");

        LatePaymentCase lateCase = intent.recordLateCapture(Money.of("120.00", "USD"), "stripe", "late-capture-1", NOW.plusSeconds(30), "DetectLatePaymentSuccess", "callback-3", "corr-3");

        assertEquals(PaymentIntentStatus.CANCELLED, intent.status(), "late capture must not recover or capture the original intent");
        assertEquals(LatePaymentCaseStatus.OPEN, lateCase.status());
        LatePaymentDetected event = assertInstanceOf(LatePaymentDetected.class, lateCase.domainEvents().getFirst());
        assertEquals(intent.paymentIntentId(), event.paymentIntentId());
    }

    @Test
    void callbackRecordsDetectDuplicateCallbacks() {
        ChannelCallbackRecord first = ChannelCallbackRecord.receive("stripe", "cb-1", "sha256:abc", "PAYMENT_CAPTURED", List.of(), NOW, "RecordChannelCallback", "corr-4");
        ChannelCallbackRecord duplicate = ChannelCallbackRecord.receive("stripe", "cb-1", "sha256:abc", "PAYMENT_CAPTURED", List.of(first), NOW.plusSeconds(1), "RecordChannelCallback", "corr-4");

        assertEquals(CallbackProcessingStatus.RECEIVED, first.status());
        assertInstanceOf(ChannelCallbackReceived.class, first.auditEvent());
        assertTrue(duplicate.isDuplicate());
        assertEquals(first.callbackRecordId(), duplicate.firstCallbackRecordId());
        assertInstanceOf(DuplicateChannelCallbackDetected.class, duplicate.auditEvent());
        assertThrows(DomainRuleViolation.class, duplicate::markApplied);
    }

    @Test
    void routesPreferredChannelAndRejectsAmountsAboveAllChannelLimits() {
        ChannelRouter router = ChannelRouter.defaults();

        PaymentChannel alipay = router.route(Money.fromMinorUnits(12_000, "CNY"), "ALIPAY");

        assertEquals("ALIPAY", alipay.channelId());
        assertEquals(30, alipay.timeoutSeconds());
        DomainRuleViolation violation = assertThrows(DomainRuleViolation.class,
            () -> router.route(Money.fromMinorUnits(1_000_001, "CNY"), "ALIPAY"));
        assertEquals("AMOUNT_EXCEEDS_CHANNEL_LIMIT", violation.getMessage());
    }

    private static PaymentIntent newIntent(String idempotencyKey) {
        return PaymentIntent.create(
            "order-123",
            "initial-booking",
            Money.of("120.00", "USD"),
            "payer-9",
            NOW.plusSeconds(900),
            idempotencyKey,
            NOW,
            "CreatePaymentIntent",
            "corr-1"
        );
    }
}
