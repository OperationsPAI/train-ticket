package com.trainticket.journeyorder.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class OrderFunnelMetricsWiringTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(Recorders.class);

    @Test
    void withoutAnOtlpMetricsEndpointTheNoopRecorderIsUsed() {
        contextRunner
            .withPropertyValues(
                "otel.metrics.exporter=none",
                "otel.exporter.otlp.endpoint=http://collector:4317",
                "otel.service.name=journey-order")
            .run(context -> {
                assertThat(context).hasSingleBean(OrderFunnelMetrics.class);
                assertThat(context.getBean(OrderFunnelMetrics.class)).isInstanceOf(NoopOrderFunnelMetrics.class);
            });
    }

    @Test
    void withOtlpMetricsTheOpenTelemetryRecorderIsUsed() {
        contextRunner
            .withPropertyValues(
                "otel.metrics.exporter=otlp",
                "otel.exporter.otlp.endpoint=http://collector:4317",
                "otel.service.name=journey-order")
            .withBean(OpenTelemetry.class, OpenTelemetry::noop)
            .run(context -> {
                assertThat(context).hasSingleBean(OrderFunnelMetrics.class);
                assertThat(context.getBean(OrderFunnelMetrics.class)).isInstanceOf(OtelOrderFunnelMetrics.class);
            });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({OtelOrderFunnelMetrics.class, NoopOrderFunnelMetrics.class})
    static class Recorders {
    }
}
