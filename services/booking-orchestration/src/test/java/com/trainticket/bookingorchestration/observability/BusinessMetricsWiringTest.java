package com.trainticket.bookingorchestration.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Exactly one recorder of each kind must exist, chosen by the OTel metrics enabled condition. Two
 * would make every injection ambiguous; zero would stop the service from starting.
 */
class BusinessMetricsWiringTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(Recorders.class);

    @Test
    void withoutAnOtlpMetricsEndpointTheNoopRecordersAreUsed() {
        contextRunner
            .withPropertyValues(
                "otel.metrics.exporter=none",
                "otel.exporter.otlp.endpoint=http://collector:4317",
                "otel.service.name=booking-orchestration")
            .run(context -> {
                assertThat(context).hasSingleBean(OrderFunnelMetrics.class);
                assertThat(context).hasSingleBean(BookingSagaMetrics.class);
                assertThat(context.getBean(OrderFunnelMetrics.class)).isInstanceOf(NoopOrderFunnelMetrics.class);
                assertThat(context.getBean(BookingSagaMetrics.class)).isInstanceOf(NoopBookingSagaMetrics.class);
            });
    }

    @Test
    void withOtlpMetricsTheOpenTelemetryRecordersAreUsed() {
        contextRunner
            .withPropertyValues(
                "otel.metrics.exporter=otlp",
                "otel.exporter.otlp.endpoint=http://collector:4317",
                "otel.service.name=booking-orchestration")
            .withBean(OpenTelemetry.class, OpenTelemetry::noop)
            .run(context -> {
                assertThat(context).hasSingleBean(OrderFunnelMetrics.class);
                assertThat(context).hasSingleBean(BookingSagaMetrics.class);
                assertThat(context.getBean(OrderFunnelMetrics.class)).isInstanceOf(OtelOrderFunnelMetrics.class);
                assertThat(context.getBean(BookingSagaMetrics.class)).isInstanceOf(OtelBookingSagaMetrics.class);
            });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({OtelOrderFunnelMetrics.class, NoopOrderFunnelMetrics.class,
        OtelBookingSagaMetrics.class, NoopBookingSagaMetrics.class})
    static class Recorders {
    }
}
