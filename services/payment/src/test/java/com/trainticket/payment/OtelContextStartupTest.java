package com.trainticket.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.trainticket.platformkit.observability.PlatformOpenTelemetryConfiguration;
import com.trainticket.platformkit.observability.SpanExporterFactory;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class OtelContextStartupTest {
    @Test
    void requestContextStartsWhenOtelTracesExporterIsOtlp() {
        new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformOpenTelemetryConfiguration.class))
            .withUserConfiguration(RequestContextOnlyConfiguration.class)
            .withPropertyValues(
                "otel.traces.exporter=otlp",
                "otel.exporter.otlp.endpoint=http://collector:4317",
                "otel.service.name=payment"
            )
            .withBean(RuntimeTracer.class, () -> new NoOpRuntimeTracer())
            .withBean(SpanExporterFactory.class, () -> endpoint -> new NoOpSpanExporter())
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(RuntimeTracer.class);
                assertThat(context).hasSingleBean(RequestContextFilter.class);
                assertThat(context).hasSingleBean(OpenTelemetry.class);
            });
    }

    @Import(RequestContextFilter.class)
    private static final class RequestContextOnlyConfiguration {
    }

    private static final class NoOpSpanExporter implements SpanExporter {
        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
