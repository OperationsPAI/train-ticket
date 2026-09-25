package com.trainticket.postsales.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import java.util.Objects;

/**
 * Publishes Post Sales' compensation outcomes through the process meter.
 *
 * <p>Only the operations counter exists: there is deliberately no amount or
 * conservation instrument here, because Post Sales does not own money.
 */
public final class OpenTelemetryPostSalesMetrics implements PostSalesMetricRecorder {
    private static final AttributeKey<String> OPERATION = AttributeKey.stringKey("operation");
    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("outcome");
    private static final AttributeKey<String> CURRENCY = AttributeKey.stringKey("currency");
    private static final AttributeKey<String> CHANNEL = AttributeKey.stringKey("channel");

    private final LongCounter operations;

    public OpenTelemetryPostSalesMetrics(Meter meter) {
        Objects.requireNonNull(meter, "meter is required");
        this.operations = meter.counterBuilder("train_ticket.payment.operations")
            .setDescription("Capture, refund, reversal and compensation operation outcomes.")
            .setUnit("{operation}")
            .build();
    }

    @Override
    public void compensation(PostSalesMetricLabels.Outcome outcome, String currency) {
        operations.add(1, Attributes.of(
            OPERATION, PostSalesMetricLabels.OPERATION_COMPENSATION,
            OUTCOME, outcome.value(),
            CURRENCY, PostSalesMetricLabels.currency(currency),
            CHANNEL, PostSalesMetricLabels.CHANNEL_WALLET
        ));
    }
}
