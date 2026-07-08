package com.trainticket.platformkit.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.api.trace.StatusCode;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

class PlatformOpenTelemetryConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PlatformOpenTelemetryConfiguration.class));

    @Test
    void otelBeansAreAbsentWhenEnvironmentContractIsMissing() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(OpenTelemetry.class));
    }

    @Test
    void completeOtelEnvironmentInitializesTracerAndProducesHttpSpan() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();

        contextRunner
            .withPropertyValues(
                "otel.traces.exporter=otlp",
                "otel.exporter.otlp.endpoint=http://collector:4317",
                "otel.service.name=java-kit-test"
            )
            .withBean(SpanExporterFactory.class, () -> endpoint -> {
                assertThat(endpoint).isEqualTo("http://collector:4317");
                return exporter;
            })
            .run(context -> {
                assertThat(context).hasSingleBean(OpenTelemetry.class);
                OtelHttpServerFilter filter = (OtelHttpServerFilter) context.getBean(org.springframework.boot.web.servlet.FilterRegistrationBean.class, OtelHttpServerFilter.class).getFilter();
                MockHttpServletRequest request = new MockHttpServletRequest("GET", "/healthz");
                request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/healthz");
                MockHttpServletResponse response = new MockHttpServletResponse();

                filter.doFilter(request, response, new MockFilterChain());

                context.getBean(SdkTracerProvider.class).forceFlush().join(10, TimeUnit.SECONDS);
                assertThat(exporter.getFinishedSpanItems())
                    .singleElement()
                    .satisfies(span -> {
                        assertThat(span.getName()).isEqualTo("GET /healthz");
                        assertThat(span.getAttributes().get(AttributeKey.stringKey("http.route"))).isEqualTo("/healthz");
                        assertThat(span.getAttributes().get(AttributeKey.longKey("http.response.status_code"))).isEqualTo(200L);
                    });
            });
    }

    @Test
    void eventConsumerSpanRecordsMessagingAttributesAndFailures() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();

        contextRunner
            .withPropertyValues(
                "otel.traces.exporter=otlp",
                "otel.exporter.otlp.endpoint=http://collector:4317",
                "otel.service.name=java-kit-test"
            )
            .withBean(SpanExporterFactory.class, () -> endpoint -> exporter)
            .run(context -> {
                EventConsumerTracer tracer = context.getBean(EventConsumerTracer.class);
                com.trainticket.platformkit.messaging.EventEnvelope envelope =
                    new com.trainticket.platformkit.messaging.EventEnvelopeFactory("payment")
                        .create("PaymentCaptured", Map.of("paymentId", "pay-1"));
                RuntimeException failure = new IllegalStateException("boom");

                try (EventConsumerTracer.SpanScope span = tracer.start("events:payment", "booking-orchestration", envelope)) {
                    span.recordException(failure);
                }

                context.getBean(SdkTracerProvider.class).forceFlush().join(10, TimeUnit.SECONDS);
                assertThat(exporter.getFinishedSpanItems())
                    .singleElement()
                    .satisfies(span -> {
                        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
                        assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.stream"))).isEqualTo("events:payment");
                        assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.consumer_group"))).isEqualTo("booking-orchestration");
                        assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.event_id"))).isEqualTo(envelope.eventId());
                        assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.event_type"))).isEqualTo("PaymentCaptured");
                        assertThat(span.getAttributes().get(AttributeKey.stringKey("messaging.correlation_id"))).isEqualTo(envelope.correlationId());
                        assertThat(span.getEvents()).anySatisfy(event -> assertThat(event.getName()).isEqualTo("exception"));
                    });
            });
    }
}
