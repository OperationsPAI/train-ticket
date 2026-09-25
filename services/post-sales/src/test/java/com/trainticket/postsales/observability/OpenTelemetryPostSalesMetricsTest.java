package com.trainticket.postsales.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OpenTelemetryPostSalesMetricsTest {
    private static final AttributeKey<String> OPERATION = AttributeKey.stringKey("operation");
    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("outcome");
    private static final AttributeKey<String> CURRENCY = AttributeKey.stringKey("currency");
    private static final AttributeKey<String> CHANNEL = AttributeKey.stringKey("channel");

    @Test
    void contributesOnlyBoundedCompensationOutcomes() {
        InMemoryMetricExporter exporter = InMemoryMetricExporter.create();
        try (SdkMeterProvider provider = provider(exporter)) {
            OpenTelemetryPostSalesMetrics metrics =
                new OpenTelemetryPostSalesMetrics(provider.get("post-sales-observability-test"));

            metrics.compensation(PostSalesMetricLabels.Outcome.PENDING, "CNY");
            metrics.compensation(PostSalesMetricLabels.Outcome.SUCCEEDED, "CNY");
            metrics.compensation(PostSalesMetricLabels.Outcome.VERIFIED, "CNY");
            metrics.compensation(PostSalesMetricLabels.Outcome.REJECTED, "USD");
            provider.forceFlush().join(10, TimeUnit.SECONDS);

            List<MetricData> exported = exporter.getFinishedMetricItems();
            // Compensation owns an outcome only; there is no amount or
            // conservation series here, so Post Sales cannot become a second
            // money source for the same wallet credit.
            assertThat(exported.stream().map(MetricData::getName))
                .containsExactly("train_ticket.payment.operations");

            MetricData operations = exported.getFirst();
            assertThat(operations.getUnit()).isEqualTo("{operation}");
            assertThat(operations.getLongSumData().getPoints())
                .hasSize(4)
                .allSatisfy(point -> {
                    assertThat(point.getAttributes().get(OPERATION)).isEqualTo("compensation");
                    assertThat(point.getAttributes().get(CHANNEL)).isEqualTo("wallet");
                    assertThat(point.getAttributes().asMap().keySet())
                        .allMatch(key -> List.of(OPERATION, OUTCOME, CURRENCY, CHANNEL).contains(key));
                });
            assertThat(operations.getLongSumData().getPoints())
                .extracting(point -> point.getAttributes().get(OUTCOME))
                .containsExactlyInAnyOrder("pending", "succeeded", "verified", "rejected");
            assertThat(operations.getLongSumData().getPoints())
                .extracting(point -> point.getAttributes().get(CURRENCY))
                .containsExactlyInAnyOrder("CNY", "CNY", "CNY", "USD");
        }
    }

    @Test
    void twoDifferentCompensationCasesShareOneLabelSet() {
        InMemoryMetricExporter exporter = InMemoryMetricExporter.create();
        try (SdkMeterProvider provider = provider(exporter)) {
            OpenTelemetryPostSalesMetrics metrics =
                new OpenTelemetryPostSalesMetrics(provider.get("post-sales-observability-test"));

            // The recorder carries no case id, so two distinct approved cases
            // with the same outcome and currency are one series.
            metrics.compensation(PostSalesMetricLabels.Outcome.SUCCEEDED, "CNY");
            metrics.compensation(PostSalesMetricLabels.Outcome.SUCCEEDED, "CNY");
            provider.forceFlush().join(10, TimeUnit.SECONDS);

            MetricData operations = exporter.getFinishedMetricItems().getFirst();
            assertThat(operations.getLongSumData().getPoints())
                .singleElement()
                .satisfies(point -> assertThat(point.getValue()).isEqualTo(2L));
        }
    }

    private static SdkMeterProvider provider(InMemoryMetricExporter exporter) {
        return SdkMeterProvider.builder()
            .registerMetricReader(PeriodicMetricReader.builder(exporter).setInterval(Duration.ofDays(1)).build())
            .build();
    }
}
