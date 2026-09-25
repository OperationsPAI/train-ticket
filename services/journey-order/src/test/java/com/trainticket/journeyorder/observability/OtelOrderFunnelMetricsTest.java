package com.trainticket.journeyorder.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OtelOrderFunnelMetricsTest {
    @Test
    void publishesTheContractNameWithOnlyItsThreeBoundedLabels() {
        InMemoryMetricExporter exporter = InMemoryMetricExporter.create();
        try (SdkMeterProvider provider = provider(exporter)) {
            OrderFunnelMetrics metrics = new OtelOrderFunnelMetrics(provider.get("journey-order-test"));
            metrics.transition(OrderFunnelMetrics.Stage.ORDER, OrderFunnelMetrics.Outcome.ATTEMPTED, OrderFunnelMetrics.Channel.WEB);
            metrics.transition(OrderFunnelMetrics.Stage.ORDER, OrderFunnelMetrics.Outcome.ACCEPTED, OrderFunnelMetrics.Channel.MOBILE);
            provider.forceFlush().join(10, TimeUnit.SECONDS);

            MetricData metric = exporter.getFinishedMetricItems().stream()
                .filter(candidate -> candidate.getName().equals("train_ticket.order.transitions"))
                .findFirst().orElseThrow();
            assertThat(metric.getUnit()).isEqualTo("{transition}");
            assertThat(metric.getLongSumData().getPoints())
                .allSatisfy(point -> assertThat(point.getAttributes().asMap().keySet().stream()
                    .map(AttributeKey::getKey)
                    .sorted()
                    .toList())
                    .containsExactly("channel", "outcome", "stage"));
        }
    }

    @Test
    void unknownVocabularyCollapsesToOtherInsteadOfLeakingItsText() {
        assertThat(OrderFunnelMetrics.Stage.fromValue("search")).isEqualTo(OrderFunnelMetrics.Stage.SEARCH);
        assertThat(OrderFunnelMetrics.Stage.fromValue("order-abc-123")).isEqualTo(OrderFunnelMetrics.Stage.OTHER);
        assertThat(OrderFunnelMetrics.Stage.fromValue(null)).isEqualTo(OrderFunnelMetrics.Stage.OTHER);
        assertThat(OrderFunnelMetrics.Outcome.fromValue("Exploded: null pointer")).isEqualTo(OrderFunnelMetrics.Outcome.OTHER);
        assertThat(OrderFunnelMetrics.Channel.fromRef("mobile")).isEqualTo(OrderFunnelMetrics.Channel.MOBILE);
        assertThat(OrderFunnelMetrics.Channel.fromRef("campaign-2026-q3")).isEqualTo(OrderFunnelMetrics.Channel.OTHER);
    }

    /**
     * Two orders collapse onto one series: the label set is what makes a series, so if an order id
     * reached the labels the count would be split and the assertion would fail.
     */
    @Test
    void twoDifferentOrdersCollapseOntoOneSeries() {
        InMemoryMetricExporter exporter = InMemoryMetricExporter.create();
        try (SdkMeterProvider provider = provider(exporter)) {
            OrderFunnelMetrics metrics = new OtelOrderFunnelMetrics(provider.get("journey-order-test"));
            metrics.transition(OrderFunnelMetrics.Stage.ORDER, OrderFunnelMetrics.Outcome.ACCEPTED, OrderFunnelMetrics.Channel.WEB);
            metrics.transition(OrderFunnelMetrics.Stage.ORDER, OrderFunnelMetrics.Outcome.ACCEPTED, OrderFunnelMetrics.Channel.WEB);
            provider.forceFlush().join(10, TimeUnit.SECONDS);

            List<MetricData> orderTransitions = exporter.getFinishedMetricItems().stream()
                .filter(candidate -> candidate.getName().equals("train_ticket.order.transitions"))
                .toList();
            assertThat(orderTransitions).singleElement().satisfies(metric ->
                assertThat(metric.getLongSumData().getPoints())
                    .singleElement()
                    .satisfies(point -> assertThat(point.getValue()).isEqualTo(2L)));
        }
    }

    private static SdkMeterProvider provider(InMemoryMetricExporter exporter) {
        return SdkMeterProvider.builder()
            .registerMetricReader(PeriodicMetricReader.builder(exporter).setInterval(Duration.ofDays(1)).build())
            .build();
    }
}
