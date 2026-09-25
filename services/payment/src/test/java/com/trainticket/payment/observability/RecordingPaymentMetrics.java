package com.trainticket.payment.observability;

import java.util.ArrayList;
import java.util.List;

/** Captures the bounded labels a recorder receives, for wiring assertions. */
public final class RecordingPaymentMetrics implements PaymentMetricRecorder {
    public record Operation(
        PaymentMetricLabels.Operation operation,
        PaymentMetricLabels.Outcome outcome,
        String currency,
        String channel
    ) {
    }

    public record Amount(
        PaymentMetricLabels.Operation operation,
        PaymentMetricLabels.Outcome outcome,
        String currency,
        String channel,
        long minorUnits
    ) {
    }

    private final List<Operation> operations = new ArrayList<>();
    private final List<Amount> amounts = new ArrayList<>();
    private List<PaymentConservationPoint> conservation = List.of();

    @Override
    public void operation(
        PaymentMetricLabels.Operation operation,
        PaymentMetricLabels.Outcome outcome,
        String currency,
        String channel
    ) {
        operations.add(new Operation(operation, outcome, PaymentMetricLabels.currency(currency), PaymentMetricLabels.channel(channel)));
    }

    @Override
    public void amount(
        PaymentMetricLabels.Operation operation,
        PaymentMetricLabels.Outcome outcome,
        String currency,
        String channel,
        long minorUnits
    ) {
        amounts.add(new Amount(operation, outcome, PaymentMetricLabels.currency(currency), PaymentMetricLabels.channel(channel), minorUnits));
    }

    @Override
    public void conservation(List<PaymentConservationPoint> points) {
        conservation = List.copyOf(points);
    }

    public List<Operation> operations() {
        return List.copyOf(operations);
    }

    public List<Amount> amounts() {
        return List.copyOf(amounts);
    }

    public List<PaymentConservationPoint> conservation() {
        return conservation;
    }
}
