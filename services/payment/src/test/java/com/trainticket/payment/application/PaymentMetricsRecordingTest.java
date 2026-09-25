package com.trainticket.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.trainticket.payment.domain.ChannelRef;
import com.trainticket.payment.domain.ChannelRouter;
import com.trainticket.payment.domain.Money;
import com.trainticket.payment.domain.PaymentChannel;
import com.trainticket.payment.domain.PaymentIntent;
import com.trainticket.payment.domain.Refund;
import com.trainticket.payment.domain.RefundStatus;
import com.trainticket.payment.infrastructure.persistence.InMemoryLatePaymentCaseRepository;
import com.trainticket.payment.infrastructure.persistence.InMemoryPaymentIntentRepository;
import com.trainticket.payment.infrastructure.persistence.InMemoryRefundRepository;
import com.trainticket.payment.infrastructure.persistence.InMemoryReservationPaymentRequestRepository;
import com.trainticket.payment.observability.PaymentMetricLabels.Operation;
import com.trainticket.payment.observability.PaymentMetricLabels.Outcome;
import com.trainticket.payment.observability.RecordingPaymentMetrics;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Proves the command service feeds the contract: the labels are exactly the
 * closed four, amounts are integer minor units, and a late collection is a
 * reversal rather than a second refund.
 */
class PaymentMetricsRecordingTest {
    @Test
    void twoIntentsCaptureUnderTheSameLabelSetAndNeverLeakTheirIds() {
        Fixture fixture = new Fixture();

        String first = fixture.captureIntent("ord-a", Money.fromMinorUnits(1_000, "CNY"));
        String second = fixture.captureIntent("ord-b", Money.fromMinorUnits(1_000, "CNY"));
        assertThat(Set.of(first, second)).hasSize(2);

        List<RecordingPaymentMetrics.Operation> captures = fixture.recorder.operations().stream()
            .filter(operation -> operation.operation() == Operation.CAPTURE)
            .toList();
        assertThat(captures).hasSize(2);
        assertThat(captures).extracting(RecordingPaymentMetrics.Operation::currency).containsOnly("CNY");
        assertThat(captures).extracting(RecordingPaymentMetrics.Operation::channel).containsOnly("alipay");
        assertThat(captures).extracting(RecordingPaymentMetrics.Operation::outcome).containsOnly(Outcome.SUCCEEDED);
        assertThat(new HashSet<>(captures))
            .as("two different payment intents must collapse to one label set")
            .hasSize(1);

        assertThat(fixture.recorder.amounts())
            .isNotEmpty()
            .allSatisfy(amount -> assertThat(amount.minorUnits()).isPositive());
    }

    @Test
    void refundsAcrossTwoCurrenciesAreNeverSummed() {
        Fixture fixture = new Fixture();

        fixture.captureAndRefund("ord-cny", Money.fromMinorUnits(1_200, "CNY"), 500);
        fixture.captureAndRefund("ord-usd", Money.fromMinorUnits(900, "USD"), 400);

        List<RecordingPaymentMetrics.Amount> refunds = fixture.recorder.amounts().stream()
            .filter(amount -> amount.operation() == Operation.REFUND)
            .toList();
        assertThat(refunds).extracting(RecordingPaymentMetrics.Amount::currency)
            .containsExactlyInAnyOrder("CNY", "USD");
        assertThat(refunds).extracting(RecordingPaymentMetrics.Amount::minorUnits)
            .containsExactlyInAnyOrder(500L, 400L);
    }

    @Test
    void lateCollectionIsRecordedAsAReversalRatherThanARefund() {
        Fixture fixture = new Fixture();
        Money collected = Money.fromMinorUnits(1_075, "CNY");
        PaymentIntent seed = PaymentIntent.create(
            "ord-late", "purchase", collected, "acct-1",
            Instant.parse("2026-07-05T10:31:00Z"), "idem-late",
            Instant.parse("2026-07-05T10:30:00Z"), "cmd-late", "corr-late");
        seed.recordChannelHandoff(new ChannelRef("ALIPAY", "co-late", null, "txn-late", null, null, null));
        fixture.intents.save(seed);

        fixture.service.cancelIntent(seed.paymentIntentId(), "cancelled", "idem-cancel", "corr-cancel");
        fixture.service.captureIntentFromChannel(
            seed.paymentIntentId(), collected, "ALIPAY", "txn-late", "co-late", "cmd-late-capture", "corr-late");
        fixture.service.prepareLatePaymentRefund(
            seed.paymentIntentId(), "ord-late", "case-late", "LATE_PAYMENT", "idem-late-refund", "corr-late-refund");
        Refund settled = fixture.service.submitLatePaymentRefund(seed.paymentIntentId(), "corr-late-settle");

        assertThat(settled.status()).isEqualTo(RefundStatus.SETTLED);
        assertThat(fixture.recorder.operations())
            .anyMatch(operation -> operation.operation() == Operation.REVERSAL && operation.outcome() == Outcome.SUCCEEDED);
        assertThat(fixture.recorder.operations())
            .noneMatch(operation -> operation.operation() == Operation.REFUND);
    }

    private static final class Fixture {
        private final RecordingPaymentMetrics recorder = new RecordingPaymentMetrics();
        private final InMemoryPaymentIntentRepository intents = new InMemoryPaymentIntentRepository();
        private final InMemoryRefundRepository refunds = new InMemoryRefundRepository();
        private final PaymentCommandService service;

        private Fixture() {
            service = new PaymentCommandService(
                Clock.fixed(Instant.parse("2026-07-05T10:30:00Z"), ZoneOffset.UTC),
                new FakeEventPublisher(),
                intents,
                refunds,
                new InMemoryReservationPaymentRequestRepository(),
                new InMemoryLatePaymentCaseRepository(),
                new NoopPaymentChannelClient(),
                new ChannelRouter(List.of(
                    new PaymentChannel("ALIPAY", "ALIPAY", 500_000, 30, true, 40),
                    new PaymentChannel("UNIONPAY", "UNIONPAY", 1_000_000, 60, true, 15))));
            service.setPaymentMetricRecorder(recorder);
        }

        private String captureIntent(String businessRef, Money amount) {
            PaymentIntent created = service.createIntent(
                businessRef, "purchase", amount, "acct-1", "idem-" + businessRef, "corr-" + businessRef, "ALIPAY");
            String intentId = created.paymentIntentId();
            String channel = service.getIntent(intentId).channelRef().channel();
            service.captureIntentFromChannel(
                intentId, amount, channel, "txn-" + businessRef, "co-" + businessRef,
                "cmd-" + businessRef, "corr-" + businessRef);
            return intentId;
        }

        private void captureAndRefund(String businessRef, Money amount, long refundMinorUnits) {
            String intentId = captureIntent(businessRef, amount);
            PaymentIntent captured = service.getIntent(intentId);
            Money refundAmount = Money.fromMinorUnits(refundMinorUnits, amount.currency().getCurrencyCode());
            Refund refund = service.requestRefund(
                intentId, refundAmount, "customer request", "case-" + businessRef,
                "idem-refund-" + businessRef, "corr-refund-" + businessRef);
            service.settleRefundFromChannel(
                refund.refundId(), intentId, refundAmount, "crf-" + businessRef,
                captured.channelRef().channelOrderId(), captured.channelRef().channel(),
                captured.channelRef().channelTransactionId(), "rftxn-" + businessRef,
                "cmd-settle-" + businessRef, "corr-settle-" + businessRef);
        }
    }
}
