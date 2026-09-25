package com.trainticket.payment.observability;

import java.util.List;

/**
 * Records the bounded {@code train_ticket.payment.*} business metrics.
 *
 * <p>The operation and amount counters are incremented together for a money
 * movement; the conservation gauge is refreshed from durable records rather
 * than from the counters, so a process restart cannot masquerade as a balance
 * reset.
 */
public interface PaymentMetricRecorder {
    void operation(
        PaymentMetricLabels.Operation operation,
        PaymentMetricLabels.Outcome outcome,
        String currency,
        String channel
    );

    void amount(
        PaymentMetricLabels.Operation operation,
        PaymentMetricLabels.Outcome outcome,
        String currency,
        String channel,
        long minorUnits
    );

    /** Replaces the last observed conservation totals. */
    void conservation(List<PaymentConservationPoint> points);
}
