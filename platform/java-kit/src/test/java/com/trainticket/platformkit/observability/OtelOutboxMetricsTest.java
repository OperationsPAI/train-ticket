package com.trainticket.platformkit.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class OtelOutboxMetricsTest {
    @Test
    void publishesBoundedBacklogStateAndFailureStages() {
        InMemoryMetricExporter exporter = InMemoryMetricExporter.create();
        try (SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(PeriodicMetricReader.builder(exporter).setInterval(Duration.ofDays(1)).build())
            .build()) {
            OutboxMetrics metrics = new OtelOutboxMetrics(provider.get("java-kit-outbox-test"));
            metrics.observeUnpublishedSample(100, Duration.ofMillis(12_500));
            metrics.recordPublished(64);
            metrics.recordFailure(OutboxMetrics.FailureStage.PUBLISH);

            provider.forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);
            List<MetricData> exported = exporter.getFinishedMetricItems();

            assertThat(longGauge(exported, "train_ticket.outbox.unpublished.sample")).isEqualTo(100L);
            assertThat(doubleGauge(exported, "train_ticket.outbox.oldest_unpublished.age")).isEqualTo(12.5);
            assertThat(longSum(exported, "train_ticket.outbox.published")).isEqualTo(64L);
            assertThat(exported)
                .filteredOn(metric -> metric.getName().equals("train_ticket.outbox.failures"))
                .singleElement()
                .satisfies(metric -> {
                    assertThat(metric.getLongSumData().getPoints()).singleElement().satisfies(point -> {
                        assertThat(point.getValue()).isEqualTo(1L);
                        assertThat(point.getAttributes().size()).isEqualTo(1);
                        assertThat(point.getAttributes().get(AttributeKey.stringKey("stage"))).isEqualTo("publish");
                    });
                });
        }
    }

    private static long longGauge(List<MetricData> metrics, String name) {
        return metrics.stream().filter(metric -> metric.getName().equals(name)).findFirst().orElseThrow()
            .getLongGaugeData().getPoints().iterator().next().getValue();
    }

    private static double doubleGauge(List<MetricData> metrics, String name) {
        return metrics.stream().filter(metric -> metric.getName().equals(name)).findFirst().orElseThrow()
            .getDoubleGaugeData().getPoints().iterator().next().getValue();
    }

    private static long longSum(List<MetricData> metrics, String name) {
        return metrics.stream().filter(metric -> metric.getName().equals(name)).findFirst().orElseThrow()
            .getLongSumData().getPoints().iterator().next().getValue();
    }
}
