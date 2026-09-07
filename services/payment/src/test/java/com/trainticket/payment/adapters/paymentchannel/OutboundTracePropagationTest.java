package com.trainticket.payment.adapters.paymentchannel;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.trainticket.payment.application.PaymentChannelClient;
import com.trainticket.payment.domain.ChannelRef;
import com.trainticket.payment.domain.Money;
import com.trainticket.payment.domain.PaymentIntent;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.client.RestClient;

/**
 * Guards the wiring the trace-propagation interceptor depends on: the client
 * must build from the container-managed RestClient.Builder, or outbound requests
 * to payment-channel silently lose their W3C traceparent and the callee opens a
 * new trace.
 *
 * Modelled on services/post-sales/.../OutboundTracePropagationTest.
 */
class OutboundTracePropagationTest {
    private static HttpServer stub;
    private static final List<Map<String, String>> captured = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void startStub() throws Exception {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/api/v1/channel-orders", exchange -> {
            captured.add(Map.of(
                "path", exchange.getRequestURI().getPath(),
                "traceparent", String.valueOf(exchange.getRequestHeaders().getFirst("traceparent")),
                "idempotency-key", String.valueOf(exchange.getRequestHeaders().getFirst("Idempotency-Key")),
                "x-correlation-id", String.valueOf(exchange.getRequestHeaders().getFirst("X-Correlation-Id"))
            ));
            // Serves both the create and the /submit call: the create response
            // needs channelOrderId/version/requestFingerprint, and submit needs
            // channelOrderId/status.
            byte[] response = ("{\"channelOrderId\":\"co-0001\",\"version\":1,"
                + "\"requestFingerprint\":\"fp-0001\",\"status\":\"SUBMITTED\","
                + "\"channelTransactionId\":\"ctx-0001\"}").getBytes(StandardCharsets.UTF_8);
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
        captured.clear();
    }

    @Test
    void handoffCaptureCarriesTraceparentOnEveryRequest() {
        new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformOpenTelemetryConfiguration.class))
            .withPropertyValues(
                "otel.traces.exporter=otlp",
                "otel.exporter.otlp.endpoint=http://collector:4317",
                "otel.service.name=payment"
            )
            .withBean(SpanExporterFactory.class, () -> endpoint -> new NoOpSpanExporter())
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .run(context -> {
                assertThat(context).hasNotFailed();
                OpenTelemetry openTelemetry = context.getBean(OpenTelemetry.class);
                HttpPaymentChannelClient client = new HttpPaymentChannelClient(
                    context.getBean(ObjectMapper.class),
                    context.getBean(RestClient.Builder.class),
                    "http://127.0.0.1:" + stub.getAddress().getPort()
                );

                Span span = openTelemetry.getTracer("payment-test").spanBuilder("outbound").startSpan();
                PaymentChannelClient.HandoffOrder handoff;
                try (Scope ignored = span.makeCurrent()) {
                    handoff = client.handoffCapture(
                        paymentIntent(),
                        "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0aa01",
                        "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0aa02",
                        "corr-0001",
                        new ChannelRef("ALIPAY", null, null, null, null, null, null)
                    );
                } finally {
                    span.end();
                }
                assertThat(handoff.channelOrderId()).isEqualTo("co-0001");

                // handoffCapture makes two hops (create then submit); the issue
                // requires a traceparent on *every* request, so assert on both.
                assertThat(captured).as("both the create and submit requests must reach the stub").hasSize(2);
                for (Map<String, String> headers : captured) {
                    assertThat(headers.get("traceparent"))
                        .as("outbound request to %s must carry the active span's W3C traceparent", headers.get("path"))
                        .isNotNull()
                        .isNotEqualTo("null")
                        .contains(span.getSpanContext().getTraceId());
                    // The headers this client already set must survive unchanged.
                    assertThat(headers.get("x-correlation-id")).isEqualTo("corr-0001");
                    assertThat(headers.get("idempotency-key")).isNotEqualTo("null").isNotBlank();
                }
            });
    }

    /**
     * The propagation only works if the container actually has a
     * RestClient.Builder bean to inject. Boot's RestClient auto-configuration is
     * not on this service's classpath, so that bean comes from
     * HttpClientConfiguration; without it the client would fail to start in the
     * cluster even though the unit test above passes.
     */
    @Test
    void containerProvidesTheBuilderTheClientInjects() {
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

    private static PaymentIntent paymentIntent() {
        Instant now = Instant.parse("2026-09-07T10:00:00Z");
        return PaymentIntent.create(
            "ord-0194f2e0-7b3e-7610-8284-5c26e8b0aa11",
            "ORDER_PAYMENT",
            Money.fromMinorUnits(1000L, "CNY"),
            "acc-0194f2e0-7b3e-7610-8284-5c26e8b0aa12",
            now.plusSeconds(3600),
            "0194f2e0-7b3e-7610-8284-5c26e8b0aa13",
            now,
            "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0aa14",
            "corr-0194f2e0-7b3e-7610-8284-5c26e8b0aa15"
        );
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
