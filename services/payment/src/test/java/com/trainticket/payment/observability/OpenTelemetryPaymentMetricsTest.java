package com.trainticket.payment.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import com.trainticket.payment.observability.PaymentMetricLabels.Operation;
import com.trainticket.payment.observability.PaymentMetricLabels.Outcome;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class OpenTelemetryPaymentMetricsTest {
    private static final AttributeKey<String> OPERATION = AttributeKey.stringKey("operation");
    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("outcome");
    private static final AttributeKey<String> CURRENCY = AttributeKey.stringKey("currency");
    private static final AttributeKey<String> CHANNEL = AttributeKey.stringKey("channel");
    private static final AttributeKey<String> STATE = AttributeKey.stringKey("state");

    @Test
    void publishesTheContractNamesUnitsAndOnlyBoundedLabels() {
        InMemoryMetricExporter exporter = InMemoryMetricExporter.create();
        try (SdkMeterProvider provider = meterProvider(exporter)) {
            OpenTelemetryPaymentMetrics metrics = new OpenTelemetryPaymentMetrics(meter(provider));

            metrics.operation(Operation.CAPTURE, Outcome.SUCCEEDED, "CNY", "ALIPAY");
            metrics.amount(Operation.CAPTURE, Outcome.SUCCEEDED, "CNY", "ALIPAY", 1_000);
            metrics.operation(Operation.REFUND, Outcome.FAILED, "USD", "some-unmapped-channel");
            flush(provider);

            List<MetricData> exported = exporter.getFinishedMetricItems();
            assertThat(exported.stream().map(MetricData::getName))
                .containsExactlyInAnyOrder("train_ticket.payment.operations", "train_ticket.payment.amount");

            MetricData operations = metric(exported, "train_ticket.payment.operations");
            assertThat(operations.getUnit()).isEqualTo("{operation}");
            MetricData amount = metric(exported, "train_ticket.payment.amount");
            assertThat(amount.getUnit()).isEqualTo("minor_units");
            assertThat(amount.getLongSumData().getPoints())
                .singleElement()
                .satisfies(point -> {
                    assertThat(point.getValue()).isEqualTo(1_000L);
                    assertThat(point.getAttributes().get(CURRENCY)).isEqualTo("CNY");
                    assertThat(point.getAttributes().get(CHANNEL)).isEqualTo("alipay");
                });

            assertThat(labels(operations)).isEqualTo(List.of("channel", "currency", "operation", "outcome"));
            assertThat(operations.getLongSumData().getPoints())
                .extracting(point -> point.getAttributes().get(CHANNEL))
                .containsExactlyInAnyOrder("alipay", "other");
        }
    }

    /**
     * Two payments with different intent ids record the same labels, so the
     * counter consolidates them into one series. If any identifier leaked into
     * the label set this would produce two data points instead of one.
     */
    @Test
    void twoBusinessIdentifiersProduceOneLabelSet() {
        InMemoryMetricExporter exporter = InMemoryMetricExporter.create();
        try (SdkMeterProvider provider = meterProvider(exporter)) {
            OpenTelemetryPaymentMetrics metrics = new OpenTelemetryPaymentMetrics(meter(provider));

            // A recorder call carries no identifier at all; the same money
            // movement for two different intents is indistinguishable by design.
            metrics.operation(Operation.CAPTURE, Outcome.SUCCEEDED, "CNY", "ALIPAY");
            metrics.operation(Operation.CAPTURE, Outcome.SUCCEEDED, "CNY", "ALIPAY");
            flush(provider);

            MetricData operations = metric(exporter.getFinishedMetricItems(), "train_ticket.payment.operations");
            assertThat(operations.getLongSumData().getPoints())
                .singleElement()
                .satisfies(point -> assertThat(point.getValue()).isEqualTo(2L));
            assertThat(labels(operations)).isEqualTo(List.of("channel", "currency", "operation", "outcome"));
        }
    }

    @Test
    void publishesConservationAsAnIntegerMinorUnitGaugePerStateAndCurrency() {
        InMemoryMetricExporter exporter = InMemoryMetricExporter.create();
        try (SdkMeterProvider provider = meterProvider(exporter)) {
            OpenTelemetryPaymentMetrics metrics = new OpenTelemetryPaymentMetrics(meter(provider));

            metrics.conservation(List.of(
                new PaymentConservationPoint(PaymentConservationPoint.CAPTURED, "CNY", 10_000),
                new PaymentConservationPoint(PaymentConservationPoint.REFUNDED, "CNY", 2_000),
                new PaymentConservationPoint(PaymentConservationPoint.REVERSED, "CNY", 0),
                new PaymentConservationPoint(PaymentConservationPoint.UNSETTLED, "CNY", 8_000)));
            flush(provider);

            MetricData conservation = metric(exporter.getFinishedMetricItems(), "train_ticket.payment.conservation");
            assertThat(conservation.getUnit()).isEqualTo("minor_units");
            assertThat(conservation.getLongGaugeData().getPoints())
                .hasSize(4)
                .extracting(point -> point.getAttributes().get(STATE))
                .containsExactlyInAnyOrder("captured", "refunded", "reversed", "unsettled");
            assertThat(conservation.getLongGaugeData().getPoints())
                .allSatisfy(point -> {
                    assertThat(point.getAttributes().get(CURRENCY)).isEqualTo("CNY");
                    assertThat(point.getValue()).isNotNegative();
                });
        }
    }

    private static List<String> labels(MetricData metric) {
        List<String> first = metric.getLongSumData().getPoints().iterator().next().getAttributes().asMap().keySet()
            .stream().map(AttributeKey::getKey).sorted().collect(Collectors.toList());
        assertThat(metric.getLongSumData().getPoints())
            .allSatisfy(point -> assertThat(point.getAttributes().asMap().keySet().stream().map(AttributeKey::getKey).sorted().collect(Collectors.toList()))
                .isEqualTo(first));
        return first;
    }

    private static MetricData metric(List<MetricData> metrics, String name) {
        return metrics.stream().filter(metric -> metric.getName().equals(name)).findFirst().orElseThrow();
    }

    private static Meter meter(SdkMeterProvider provider) {
        return provider.get("payment-observability-test");
    }

    private static SdkMeterProvider meterProvider(InMemoryMetricExporter exporter) {
        return SdkMeterProvider.builder()
            .registerMetricReader(PeriodicMetricReader.builder(exporter).setInterval(Duration.ofDays(1)).build())
            .build();
    }

    private static void flush(SdkMeterProvider provider) {
        provider.forceFlush().join(10, TimeUnit.SECONDS);
    }
}
