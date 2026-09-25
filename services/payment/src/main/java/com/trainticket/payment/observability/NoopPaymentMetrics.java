package com.trainticket.payment.observability;

import java.util.List;

/** The recorder a payment process without an OpenTelemetry meter uses. */
public final class NoopPaymentMetrics implements PaymentMetricRecorder {
    public static final NoopPaymentMetrics INSTANCE = new NoopPaymentMetrics();

    private NoopPaymentMetrics() {
    }

    @Override
    public void operation(
        PaymentMetricLabels.Operation operation,
        PaymentMetricLabels.Outcome outcome,
        String currency,
        String channel
    ) {
    }

    @Override
    public void amount(
        PaymentMetricLabels.Operation operation,
        PaymentMetricLabels.Outcome outcome,
        String currency,
        String channel,
        long minorUnits
    ) {
    }

    @Override
    public void conservation(List<PaymentConservationPoint> points) {
    }
}
