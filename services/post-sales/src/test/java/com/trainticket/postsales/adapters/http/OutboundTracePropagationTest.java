package com.trainticket.postsales.adapters.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.trainticket.platformkit.observability.PlatformOpenTelemetryConfiguration;
import com.trainticket.platformkit.observability.SpanExporterFactory;
import com.trainticket.postsales.application.AdjustmentQuotePort.AdjustmentQuoteRequest;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.client.RestClient;

/**
 * Guards the wiring the trace-propagation interceptor depends on: the client
 * must build from the container-managed RestClient.Builder, or outbound
 * requests silently lose their W3C traceparent header.
 */
class OutboundTracePropagationTest {
    private static HttpServer stub;
    private static final AtomicReference<String> capturedTraceparent = new AtomicReference<>();

    @BeforeAll
    static void startStub() throws Exception {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/api/v1/adjustment-quotes", exchange -> {
            capturedTraceparent.set(exchange.getRequestHeaders().getFirst("traceparent"));
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
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

    @Test
    void outboundRequestCarriesTraceparentWhenTracingEnabled() {
        new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformOpenTelemetryConfiguration.class))
            .withPropertyValues(
                "otel.traces.exporter=otlp",
                "otel.exporter.otlp.endpoint=http://collector:4317",
                "otel.service.name=post-sales",
                "post-sales.fare-pricing.base-url=http://127.0.0.1:" + stub.getAddress().getPort()
            )
            .withBean(SpanExporterFactory.class, () -> endpoint -> new NoOpSpanExporter())
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withBean(FarePricingAdjustmentQuoteClient.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                OpenTelemetry openTelemetry = context.getBean(OpenTelemetry.class);
                FarePricingAdjustmentQuoteClient client = context.getBean(FarePricingAdjustmentQuoteClient.class);
                Span span = openTelemetry.getTracer("post-sales-test").spanBuilder("outbound").startSpan();
                try (Scope ignored = span.makeCurrent()) {
                    client.compute(new AdjustmentQuoteRequest(
                        "REFUND", List.of("ent-0001"), "ord-0001", List.of("seg-0001"), "idem-0001"));
                } finally {
                    span.end();
                }
                assertThat(capturedTraceparent.get())
                    .as("outbound request must carry the active span's W3C traceparent")
                    .isNotNull()
                    .contains(span.getSpanContext().getTraceId());
            });
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
