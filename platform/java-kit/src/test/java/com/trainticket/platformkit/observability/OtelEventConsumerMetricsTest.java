package com.trainticket.platformkit.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class OtelEventConsumerMetricsTest {
    @Test
    void publishesOnlyBoundedOutcomeAndFailureAttributes() {
        InMemoryMetricExporter exporter = InMemoryMetricExporter.create();
        try (SdkMeterProvider provider = SdkMeterProvider.builder()
            .registerMetricReader(PeriodicMetricReader.builder(exporter).setInterval(Duration.ofDays(1)).build())
            .build()) {
            Meter meter = provider.get("java-kit-event-consumer-test");
            EventConsumerMetrics metrics = new OtelEventConsumerMetrics(meter);

            metrics.recordOutcome(EventConsumerMetrics.Outcome.SUCCESS);
            metrics.recordOutcome(EventConsumerMetrics.Outcome.RETRY);
            metrics.recordFailure(EventConsumerMetrics.FailureType.TRANSIENT_RESULT);
            metrics.recordDeadLetter(EventConsumerMetrics.FailureType.FATAL_RESULT);

            provider.forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);
            List<MetricData> exported = exporter.getFinishedMetricItems();

            assertThat(exported.stream().map(MetricData::getName)).containsExactlyInAnyOrder(
                "train_ticket.event.consumer.messages",
                "train_ticket.event.consumer.failures",
                "train_ticket.event.consumer.dead_letters"
            );
            assertThat(attributeValues(exported, "train_ticket.event.consumer.messages", "outcome"))
                .containsExactlyInAnyOrder("success", "retry");
            assertThat(attributeValues(exported, "train_ticket.event.consumer.failures", "failure.type"))
                .containsExactly("transient_result");
            assertThat(attributeValues(exported, "train_ticket.event.consumer.dead_letters", "reason"))
                .containsExactly("fatal_result");
            assertThat(exported)
                .allSatisfy(metric -> metric.getLongSumData().getPoints().forEach(point ->
                    assertThat(point.getAttributes().size()).isEqualTo(1)));
        }
    }

    private static List<String> attributeValues(List<MetricData> metrics, String metricName, String attributeName) {
        AttributeKey<String> key = AttributeKey.stringKey(attributeName);
        return metrics.stream()
            .filter(metric -> metric.getName().equals(metricName))
            .flatMap(metric -> metric.getLongSumData().getPoints().stream())
            .map(point -> point.getAttributes().get(key))
            .toList();
    }
}
