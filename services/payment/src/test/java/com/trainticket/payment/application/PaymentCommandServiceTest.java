package com.trainticket.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.trainticket.payment.domain.ChannelRef;
import com.trainticket.payment.domain.ChannelRouter;
import com.trainticket.payment.domain.Money;
import com.trainticket.payment.domain.PaymentChannel;
import com.trainticket.payment.domain.PaymentIntent;
import com.trainticket.payment.domain.Refund;
import com.trainticket.payment.domain.RefundStatus;
import com.trainticket.payment.infrastructure.persistence.InMemoryPaymentIntentRepository;
import com.trainticket.payment.infrastructure.persistence.InMemoryRefundRepository;
import com.trainticket.payment.infrastructure.persistence.InMemoryReservationPaymentRequestRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaymentCommandServiceTest {
    @Test
    void holdsRefundForManualReviewWhenOriginalChannelIsUnavailable() {
        InMemoryPaymentIntentRepository intents = new InMemoryPaymentIntentRepository();
        InMemoryRefundRepository refunds = new InMemoryRefundRepository();
        FakeEventPublisher publisher = new FakeEventPublisher();
        ChannelRouter router = new ChannelRouter(List.of(
            new PaymentChannel("ALIPAY", "ALIPAY", 500_000, 30, false, 40),
            new PaymentChannel("UNIONPAY", "UNIONPAY", 1_000_000, 60, true, 15)
        ));
        PaymentCommandService service = new PaymentCommandService(
            Clock.fixed(Instant.parse("2026-07-05T10:30:00Z"), ZoneOffset.UTC),
            publisher,
            intents,
            refunds,
            new InMemoryReservationPaymentRequestRepository(),
            new NoopPaymentChannelClient(),
            router
        );
        PaymentIntent intent = PaymentIntent.create(
            "ord-hold",
            "purchase",
            Money.fromMinorUnits(1_000, "CNY"),
            "acct-1",
            Instant.parse("2026-07-05T10:31:00Z"),
            "idem-create",
            Instant.parse("2026-07-05T10:30:00Z"),
            "cmd-create",
            "corr-create"
        );
        intent.recordChannelHandoff(new ChannelRef("ALIPAY", "co-1", null, null, null, null, null));
        intent.capture(Money.fromMinorUnits(1_000, "CNY"), "ALIPAY", "txn-1", Instant.parse("2026-07-05T10:30:10Z"), "cmd-capture", "evt-capture", "corr-create");
        intents.save(intent);

        Refund refund = service.requestRefund(
            intent.paymentIntentId(),
            Money.fromMinorUnits(500, "CNY"),
            "ticket refund",
            "case-1",
            "idem-refund",
            "corr-refund",
            new ChannelRef("ALIPAY", "co-1", null, "txn-1", null, null, null)
        );

        assertEquals(RefundStatus.MANUAL_REVIEW_REQUIRED, refund.status());
        assertEquals("RefundFailed", publisher.published().getLast().eventType());
    }
}
