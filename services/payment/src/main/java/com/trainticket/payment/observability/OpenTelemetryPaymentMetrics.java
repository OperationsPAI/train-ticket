package com.trainticket.payment.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Publishes the payment business contract through the process's OpenTelemetry
 * meter.
 *
 * <p>Amounts are integer minor units; the unit {@code minor_units} is what makes
 * the OTLP exporter name the series {@code train_ticket_payment_amount_minor_units_total}
 * and {@code train_ticket_payment_conservation_minor_units}, matching the
 * recording rules. The conservation gauge reads a snapshot the owner replaces
 * atomically, so a collection is always a consistent set of points.
 */
public final class OpenTelemetryPaymentMetrics implements PaymentMetricRecorder {
    private static final AttributeKey<String> OPERATION = AttributeKey.stringKey("operation");
    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("outcome");
    private static final AttributeKey<String> CURRENCY = AttributeKey.stringKey("currency");
    private static final AttributeKey<String> CHANNEL = AttributeKey.stringKey("channel");
    private static final AttributeKey<String> STATE = AttributeKey.stringKey("state");

    private final LongCounter operations;
    private final LongCounter amount;
    private volatile Map<String, PaymentConservationPoint> conservation = Map.of();

    public OpenTelemetryPaymentMetrics(Meter meter) {
        Objects.requireNonNull(meter, "meter is required");
        this.operations = meter.counterBuilder("train_ticket.payment.operations")
            .setDescription("Capture, refund, reversal and compensation operation outcomes.")
            .setUnit("{operation}")
            .build();
        this.amount = meter.counterBuilder("train_ticket.payment.amount")
            .setDescription("Amount processed by payment operations, in integer minor units.")
            .setUnit("minor_units")
            .build();
        meter.gaugeBuilder("train_ticket.payment.conservation")
            .ofLongs()
            .setDescription("Authoritative captured, refunded, reversed and unsettled totals by currency.")
            .setUnit("minor_units")
            .buildWithCallback(measurement -> {
                for (PaymentConservationPoint point : conservation.values()) {
                    measurement.record(point.minorUnits(), Attributes.of(
                        STATE, point.state(),
                        CURRENCY, point.currency()
                    ));
                }
            });
    }

    @Override
    public void operation(
        PaymentMetricLabels.Operation operation,
        PaymentMetricLabels.Outcome outcome,
        String currency,
        String channel
    ) {
        operations.add(1, labels(operation, outcome, currency, channel));
    }

    @Override
    public void amount(
        PaymentMetricLabels.Operation operation,
        PaymentMetricLabels.Outcome outcome,
        String currency,
        String channel,
        long minorUnits
    ) {
        if (minorUnits <= 0) {
            return;
        }
        amount.add(minorUnits, labels(operation, outcome, currency, channel));
    }

    @Override
    public void conservation(List<PaymentConservationPoint> points) {
        Map<String, PaymentConservationPoint> next = new LinkedHashMap<>();
        for (PaymentConservationPoint point : points) {
            next.put(point.currency() + "/" + point.state(), point);
        }
        conservation = Map.copyOf(next);
    }

    private static Attributes labels(
        PaymentMetricLabels.Operation operation,
        PaymentMetricLabels.Outcome outcome,
        String currency,
        String channel
    ) {
        return Attributes.of(
            OPERATION, operation.value(),
            OUTCOME, outcome.value(),
            CURRENCY, PaymentMetricLabels.currency(currency),
            CHANNEL, PaymentMetricLabels.channel(channel)
        );
    }
}
