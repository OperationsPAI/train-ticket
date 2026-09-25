package com.trainticket.platformkit.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProcessOpenTelemetryTest {
    @AfterEach
    void resetProcessHandle() {
        ProcessOpenTelemetry.resetForTest();
    }

    @Test
    void metricsCreatedBeforeLifecycleRegistrationRebindToTheRealSdk() {
        InMemoryMetricExporter exporter = InMemoryMetricExporter.create();
        try (SdkMeterProvider meterProvider = SdkMeterProvider.builder()
            .registerMetricReader(PeriodicMetricReader.builder(exporter).setInterval(Duration.ofDays(1)).build())
            .build()) {
            OpenTelemetry sdk = OpenTelemetrySdk.builder().setMeterProvider(meterProvider).build();
            GlobalOutboxMetrics metrics = new GlobalOutboxMetrics("early-outbox-test");

            // The relay may poll before SmartLifecycle.start(). This must remain a harmless no-op,
            // not lock GlobalOpenTelemetry and not pin this recorder to the no-op meter forever.
            metrics.recordPublished(1);
            ProcessOpenTelemetry.register(sdk);
            metrics.recordPublished(2);

            meterProvider.forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(exporter.getFinishedMetricItems())
                .filteredOn(metric -> metric.getName().equals("train_ticket.outbox.published"))
                .singleElement()
                .extracting(ProcessOpenTelemetryTest::sumValue)
                .isEqualTo(2L);
        }
    }

    private static long sumValue(MetricData metric) {
        return metric.getLongSumData().getPoints().iterator().next().getValue();
    }
}
