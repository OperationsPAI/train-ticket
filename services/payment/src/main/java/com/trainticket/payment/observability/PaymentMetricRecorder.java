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

    /**
     * The age of the oldest intent the expiry sweep still owes an expiry.
     *
     * <p>The sweep runs every second and nothing published whether it was
     * keeping up: an intent past its expiry is money the customer may still be
     * authorized against, and the only durable evidence of the wait was the row
     * itself. Rebuilt from that row on every sweep, so a restart republishes the
     * same reading rather than resetting it.
     *
     * <p>An empty backlog clears the reading rather than holding the last one:
     * a stale positive age would report a queue that is not there. Nothing is
     * published at all in that case, which is the house rule for these gauges.
     */
    void oldestDueIntentAge(java.util.Optional<java.time.Duration> age);
}
