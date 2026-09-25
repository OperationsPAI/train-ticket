package com.trainticket.payment.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PaymentConservationTest {
    private static final PaymentConservationPoint CNY_CAPTURED =
        new PaymentConservationPoint(PaymentConservationPoint.CAPTURED, "CNY", 10_000);
    private static final PaymentConservationPoint CNY_REFUNDED =
        new PaymentConservationPoint(PaymentConservationPoint.REFUNDED, "CNY", 2_000);
    private static final PaymentConservationPoint CNY_REVERSED =
        new PaymentConservationPoint(PaymentConservationPoint.REVERSED, "CNY", 0);
    private static final PaymentConservationPoint CNY_UNSETTLED =
        new PaymentConservationPoint(PaymentConservationPoint.UNSETTLED, "CNY", 8_000);
    private static final PaymentConservationPoint USD_CAPTURED =
        new PaymentConservationPoint(PaymentConservationPoint.CAPTURED, "USD", 4_500);
    private static final PaymentConservationPoint USD_REFUNDED =
        new PaymentConservationPoint(PaymentConservationPoint.REFUNDED, "USD", 500);
    private static final PaymentConservationPoint USD_REVERSED =
        new PaymentConservationPoint(PaymentConservationPoint.REVERSED, "USD", 1_000);
    private static final PaymentConservationPoint USD_UNSETTLED =
        new PaymentConservationPoint(PaymentConservationPoint.UNSETTLED, "USD", 3_000);

    /**
     * The contract invariant, exercised independently for two currencies:
     * captured - refunded - reversed equals unsettled, and the two currencies
     * never combine into a third total.
     */
    @Test
    void conservesCapturedRefundedReversedAndUnsettledPerCurrency() {
        CurrencyConservation cny = new CurrencyConservation("CNY", 10_000, 2_000, 0);
        CurrencyConservation usd = new CurrencyConservation("USD", 4_500, 500, 1_000);

        for (CurrencyConservation total : java.util.List.of(cny, usd)) {
            assertThat(total.capturedMinorUnits())
                .isEqualTo(total.refundedMinorUnits() + total.reversedMinorUnits() + total.unsettledMinorUnits());
        }
        assertThat(cny.unsettledMinorUnits()).isEqualTo(8_000);
        assertThat(usd.unsettledMinorUnits()).isEqualTo(3_000);

        assertThat(PaymentConservationProjection.project(java.util.List.of(cny, usd)))
            .containsExactlyInAnyOrder(
                CNY_CAPTURED, CNY_REFUNDED, CNY_REVERSED, CNY_UNSETTLED,
                USD_CAPTURED, USD_REFUNDED, USD_REVERSED, USD_UNSETTLED);
    }

    @Test
    void doesNotSumAcrossCurrencies() {
        var points = PaymentConservationProjection.project(java.util.List.of(
            new CurrencyConservation("CNY", 10_000, 2_000, 0),
            new CurrencyConservation("USD", 4_500, 500, 1_000)));

        // 14_500 is the CNY+USD captured total. Emitting it would mean the
        // currencies had been added; the gauge must never contain it.
        assertThat(points).noneMatch(point -> point.minorUnits() == 14_500);
        assertThat(points).allSatisfy(point -> assertThat(point.currency()).isIn("CNY", "USD"));
    }

    /**
     * A restart with an empty in-process state must republish the durable
     * balance, not a zeroed one. Both processes read the same ledger, and the
     * second one is constructed from scratch.
     */
    @Test
    void reconstructsTheGaugeFromDurableStateAfterRestart() {
        PaymentConservationLedger durable =
            () -> java.util.List.of(new CurrencyConservation("CNY", 10_000, 2_000, 0));

        RecordingPaymentMetrics before = new RecordingPaymentMetrics();
        new PaymentConservationService(durable, before, 0, 60_000).refresh();
        assertThat(before.conservation())
            .containsExactlyInAnyOrder(CNY_CAPTURED, CNY_REFUNDED, CNY_REVERSED, CNY_UNSETTLED)
            .isNotEmpty();

        RecordingPaymentMetrics afterRestart = new RecordingPaymentMetrics();
        new PaymentConservationService(durable, afterRestart, 0, 60_000).refresh();

        assertThat(afterRestart.conservation())
            .isEqualTo(before.conservation())
            .contains(CNY_UNSETTLED);
    }

    @Test
    void keepsTheLastReadingWhenTheLedgerQueryFails() {
        boolean[] failing = {false};
        PaymentConservationLedger flaky = () -> {
            if (failing[0]) {
                throw new IllegalStateException("payment database unavailable");
            }
            return java.util.List.of(new CurrencyConservation("CNY", 10_000, 2_000, 0));
        };
        RecordingPaymentMetrics recorder = new RecordingPaymentMetrics();
        PaymentConservationService service = new PaymentConservationService(flaky, recorder, 0, 60_000);

        service.refresh();
        var healthy = recorder.conservation();
        failing[0] = true;
        service.refresh();

        assertThat(recorder.conservation()).isEqualTo(healthy).contains(CNY_UNSETTLED);
    }

    @Test
    void labelsCollapseUnknownValuesToOther() {
        assertThat(PaymentMetricLabels.currency("cny")).isEqualTo("CNY");
        assertThat(PaymentMetricLabels.currency("nonsense")).isEqualTo("other");
        assertThat(PaymentMetricLabels.currency(null)).isEqualTo("other");
        assertThat(PaymentMetricLabels.channel("ALIPAY_SIM")).isEqualTo("alipay");
        assertThat(PaymentMetricLabels.channel("WECHAT_PAY")).isEqualTo("wechat_pay");
        assertThat(PaymentMetricLabels.channel("internal-bank-rail-7")).isEqualTo("other");
    }
}
