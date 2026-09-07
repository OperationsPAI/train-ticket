package com.trainticket.journeyorder.infrastructure.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.trainticket.journeyorder.application.port.in.JourneyOrderRequest;
import com.trainticket.platformkit.observability.PlatformOpenTelemetryConfiguration;
import com.trainticket.platformkit.observability.SpanExporterFactory;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.client.RestClient;

/**
 * Guards the wiring the trace-propagation interceptor depends on: the adapter
 * must build from the container-managed RestClient.Builder, or outbound requests
 * to identity-verification silently lose their W3C traceparent and the callee
 * opens a new trace.
 *
 * Modelled on services/post-sales/.../OutboundTracePropagationTest.
 */
class OutboundTracePropagationTest {
    private static HttpServer stub;
    private static final AtomicReference<Map<String, String>> capturedHeaders = new AtomicReference<>();

    @BeforeAll
    static void startStub() throws Exception {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/api/v1/identity-verification/pre-order-checks", exchange -> {
            capturedHeaders.set(Map.of(
                "traceparent", String.valueOf(exchange.getRequestHeaders().getFirst("traceparent")),
                "idempotency-key", String.valueOf(exchange.getRequestHeaders().getFirst("Idempotency-Key")),
                "x-correlation-id", String.valueOf(exchange.getRequestHeaders().getFirst("X-Correlation-Id"))
            ));
            byte[] response = "{\"result\":\"PASS\",\"preOrderCheckId\":\"poc-0001\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        stub.start();
    }

    @AfterAll
    static void stopStub() {
        stub.stop(0);
    }

    @BeforeEach
    void resetCapture() {
        capturedHeaders.set(null);
    }

    @Test
    void preOrderCheckCarriesTraceparentWhenTracingEnabled() {
        runWithTracing((openTelemetry, adapter) -> {
            Span span = openTelemetry.getTracer("journey-order-test").spanBuilder("outbound").startSpan();
            try (Scope ignored = span.makeCurrent()) {
                adapter.preOrderCheck(orderRequest(), "oi-0001", "idem-0001", "corr-0001");
            } finally {
                span.end();
            }
            Map<String, String> headers = capturedHeaders.get();
            assertThat(headers).as("the stub must have received a request").isNotNull();
            assertThat(headers.get("traceparent"))
                .as("outbound request must carry the active span's W3C traceparent")
                .isNotNull()
                .isNotEqualTo("null")
                .contains(span.getSpanContext().getTraceId());
            // The headers this adapter already set must survive unchanged.
            assertThat(headers.get("idempotency-key")).isEqualTo("idem-0001");
            assertThat(headers.get("x-correlation-id")).isEqualTo("corr-0001");
        });
    }

    /**
     * The propagation only works if the container actually has a
     * RestClient.Builder bean to inject. Boot's RestClient auto-configuration is
     * not on this service's classpath, so that bean comes from
     * HttpClientConfiguration; without it the adapter would fail to start in the
     * cluster even though the unit test above passes.
     */
    @Test
    void containerProvidesTheBuilderTheAdapterInjects() {
        new WebApplicationContextRunner()
            .withUserConfiguration(HttpClientConfiguration.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(RestClient.Builder.class);
                // Prototype-scoped: each injection point must get its own builder,
                // or one client's base URL leaks into the next.
                assertThat(context.getBean(RestClient.Builder.class))
                    .isNotSameAs(context.getBean(RestClient.Builder.class));
            });
    }

    private static JourneyOrderRequest orderRequest() {        return new JourneyOrderRequest(
            "acc-0001",
            "off-0001",
            1,
            List.of("tvl-0001"),
            List.of("seg-0001"),
            "2026-09-08",
            "TRAIN"
        );
    }

    private void runWithTracing(TracingAssertion assertion) {
        new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformOpenTelemetryConfiguration.class))
            .withPropertyValues(
                "otel.traces.exporter=otlp",
                "otel.exporter.otlp.endpoint=http://collector:4317",
                "otel.service.name=journey-order"
            )
            .withBean(SpanExporterFactory.class, () -> endpoint -> new NoOpSpanExporter())
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .run(context -> {
                assertThat(context).hasNotFailed();
                OpenTelemetry openTelemetry = context.getBean(OpenTelemetry.class);
                // Constructed here rather than as a bean so the stub's ephemeral
                // port can be passed in.
                HttpIdentityVerificationAdapter adapter = new HttpIdentityVerificationAdapter(
                    context.getBean(ObjectMapper.class),
                    context.getBean(RestClient.Builder.class),
                    "http://127.0.0.1:" + stub.getAddress().getPort()
                );
                assertion.run(openTelemetry, adapter);
            });
    }

    private interface TracingAssertion {
        void run(OpenTelemetry openTelemetry, HttpIdentityVerificationAdapter adapter);
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
