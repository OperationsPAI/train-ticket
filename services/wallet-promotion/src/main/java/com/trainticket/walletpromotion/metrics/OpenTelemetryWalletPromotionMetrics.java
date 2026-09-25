package com.trainticket.walletpromotion.metrics;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** The OpenTelemetry instruments for the wallet business contract. */
public final class OpenTelemetryWalletPromotionMetrics implements WalletPromotionMetrics {
    private static final AttributeKey<String> STATE = AttributeKey.stringKey("state");
    private static final AttributeKey<String> CURRENCY = AttributeKey.stringKey("currency");
    private static final AttributeKey<String> OPERATION = AttributeKey.stringKey("operation");
    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("outcome");
    private static final AttributeKey<String> REASON_CODE = AttributeKey.stringKey("reason_code");

    private final LongCounter operations;

    public OpenTelemetryWalletPromotionMetrics(Meter meter, WalletBalanceSnapshotProvider snapshotProvider) {
        Objects.requireNonNull(meter, "meter is required");
        Objects.requireNonNull(snapshotProvider, "snapshotProvider is required");
        meter.gaugeBuilder("train_ticket.wallet.balance")
            .ofLongs()
            .setDescription("Available, reserved, redeemed, expired and reversed wallet balances, in integer minor units.")
            .setUnit("minor_units")
            .buildWithCallback(measurement -> {
                List<WalletBalanceSample> samples;
                try {
                    samples = snapshotProvider.snapshot();
                } catch (RuntimeException unavailable) {
                    // A failed read reports no series, which is honest ("unknown"),
                    // rather than a stale or fabricated zero.
                    return;
                }
                for (WalletBalanceSample sample : samples) {
                    measurement.record(sample.minorUnits(), Attributes.of(
                        STATE, sample.state().value(),
                        CURRENCY, sample.currency()
                    ));
                }
            });
        this.operations = meter.counterBuilder("train_ticket.wallet.operations")
            .setDescription("Wallet benefit operation outcomes by bounded operation, outcome, reason and currency.")
            .setUnit("{operation}")
            .build();
    }

    @Override
    public void operation(Operation operation, Outcome outcome, ReasonCode reasonCode, String currency) {
        if (currency == null || currency.isBlank()) {
            // Integer minor units always carry a currency; without one there is no
            // valid point to record.
            return;
        }
        operations.add(1, Attributes.of(
            OPERATION, operation.value(),
            OUTCOME, outcome.value(),
            REASON_CODE, reasonCode.value(),
            CURRENCY, currency.trim().toUpperCase(Locale.ROOT)
        ));
    }
}
