package com.trainticket.walletpromotion.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OpenTelemetryWalletPromotionMetricsTest {
    private static final AttributeKey<String> STATE = AttributeKey.stringKey("state");
    private static final AttributeKey<String> CURRENCY = AttributeKey.stringKey("currency");
    private static final AttributeKey<String> OPERATION = AttributeKey.stringKey("operation");
    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("outcome");
    private static final AttributeKey<String> REASON_CODE = AttributeKey.stringKey("reason_code");

    @Test
    void publishesMinorUnitBalancesAndBoundedOperationLabels() {
        CapturingMetricExporter exporter = new CapturingMetricExporter();
        try (SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(PeriodicMetricReader.builder(exporter).setInterval(Duration.ofDays(1)).build())
            .build()) {
            WalletPromotionMetrics metrics = new OpenTelemetryWalletPromotionMetrics(
                provider.get("wallet-promotion-test"),
                // Two currencies and two states, one of them a genuine zero level.
                () -> List.of(
                    new WalletBalanceSample(WalletBalanceState.AVAILABLE, "usd", 1250),
                    new WalletBalanceSample(WalletBalanceState.AVAILABLE, "EUR", 900),
                    new WalletBalanceSample(WalletBalanceState.EXPIRED, "USD", 0)
                )
            );
            // The same operation recorded for two different benefits must collapse
            // to one series; the currency arrives unnormalised and is canonicalised.
            metrics.operation(WalletPromotionMetrics.Operation.ISSUE, WalletPromotionMetrics.Outcome.SUCCESS, WalletPromotionMetrics.ReasonCode.POST_SALES_COMP, "usd");
            metrics.operation(WalletPromotionMetrics.Operation.ISSUE, WalletPromotionMetrics.Outcome.SUCCESS, WalletPromotionMetrics.ReasonCode.POST_SALES_COMP, "USD");

            provider.forceFlush().join(10, TimeUnit.SECONDS);

            MetricData balance = exporter.metric("train_ticket.wallet.balance");
            assertThat(balance.getUnit()).isEqualTo("minor_units");
            assertThat(balance.getLongGaugeData().getPoints())
                .extracting(point -> point.getAttributes().get(STATE) + "|" + point.getAttributes().get(CURRENCY) + "=" + point.getValue())
                .containsExactlyInAnyOrder("available|USD=1250", "available|EUR=900", "expired|USD=0");

            MetricData operations = exporter.metric("train_ticket.wallet.operations");
            assertThat(operations.getLongSumData().getPoints()).singleElement().satisfies(point -> {
                assertThat(point.getValue()).isEqualTo(2);
                assertThat(point.getAttributes().size()).isEqualTo(4);
                assertThat(point.getAttributes().get(OPERATION)).isEqualTo("issue");
                assertThat(point.getAttributes().get(OUTCOME)).isEqualTo("success");
                assertThat(point.getAttributes().get(REASON_CODE)).isEqualTo("post_sales_comp");
                assertThat(point.getAttributes().get(CURRENCY)).isEqualTo("USD");
            });
        }
    }

    @Test
    void mapsEveryDomainReasonToTheOwnedVocabularyAndOtherwiseFallsBackToOther() {
        assertThat(WalletPromotionMetrics.ReasonCode.from(null)).isEqualTo(WalletPromotionMetrics.ReasonCode.OTHER);
        assertThat(WalletPromotionMetrics.ReasonCode.from(reason(com.trainticket.walletpromotion.domain.ReasonType.MANUAL_OPS)))
            .isEqualTo(WalletPromotionMetrics.ReasonCode.MANUAL_OPS);
        assertThat(WalletPromotionMetrics.ReasonCode.from(reason(com.trainticket.walletpromotion.domain.ReasonType.SYSTEM_EXPIRY)))
            .isEqualTo(WalletPromotionMetrics.ReasonCode.SYSTEM_EXPIRY);
    }

    private static com.trainticket.walletpromotion.domain.BusinessReason reason(com.trainticket.walletpromotion.domain.ReasonType type) {
        return new com.trainticket.walletpromotion.domain.BusinessReason(type, "CODE", "MANUAL_ACTION", "act-1", null);
    }
}
