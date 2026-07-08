package com.trainticket.platformkit.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.platformkit.observability.OtelEventConsumerTracer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EventEnvelopeTraceContextTest {
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @AfterEach
    void resetGlobalOpenTelemetry() {
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    void absentOpenTelemetryLeavesSerializationByteForByteUnchanged() throws Exception {
        EventEnvelope envelope = factory().create("PaymentCaptured", correlationId(), causationId(), Map.of("paymentIntentId", "pi-1"));

        assertThat(envelope.traceparent()).isNull();
        assertThat(envelope.tracestate()).isNull();
        assertThat(objectMapper.writeValueAsString(envelope)).isEqualTo(
            "{\"eventId\":\"" + envelope.eventId() + "\","
                + "\"eventType\":\"PaymentCaptured\","
                + "\"occurredAt\":\"2026-07-05T10:30:00Z\","
                + "\"correlationId\":\"" + envelope.correlationId() + "\","
                + "\"causationId\":\"" + envelope.causationId() + "\","
                + "\"producer\":\"payment\","
                + "\"schemaVersion\":1,"
                + "\"payload\":{\"paymentIntentId\":\"pi-1\"}}"
        );
    }

    @Test
    void injectsTraceContextWhenOpenTelemetrySpanIsCurrent() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        OpenTelemetry openTelemetry = openTelemetry(exporter);
        GlobalOpenTelemetry.set(openTelemetry);
        Span producerSpan = tracer(openTelemetry).spanBuilder("POST /payments").setSpanKind(SpanKind.SERVER).startSpan();

        EventEnvelope envelope;
        try (Scope ignored = producerSpan.makeCurrent()) {
            envelope = factory().create("PaymentCaptured", correlationId(), causationId(), Map.of("paymentIntentId", "pi-1"));
        } finally {
            producerSpan.end();
        }

        assertThat(envelope.traceparent()).startsWith("00-" + producerSpan.getSpanContext().getTraceId() + "-");
        assertThat(envelope.tracestate()).isNull();
    }

    @Test
    void deserializeNewEnvelopeWithTraceContextAndOldEnvelopeWithoutIt() throws Exception {
        String newEnvelopeJson = "{"
            + "\"eventId\":\"evt-0194f2e0-7b3e-7610-8284-5c26e8b0c222\","
            + "\"eventType\":\"PaymentCaptured\","
            + "\"occurredAt\":\"2026-07-05T10:30:00Z\","
            + "\"correlationId\":\"corr-0194f2e0-7b3e-7610-8284-5c26e8b0c444\","
            + "\"causationId\":\"cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c555\","
            + "\"producer\":\"payment\","
            + "\"schemaVersion\":1,"
            + "\"payload\":{},"
            + "\"traceparent\":\"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01\","
            + "\"tracestate\":\"vendor=value\","
            + "\"futureField\":\"ignored\""
            + "}";
        EventEnvelope newEnvelope = objectMapper.readValue(newEnvelopeJson, EventEnvelope.class);
        EventEnvelope oldEnvelope = objectMapper.readValue(
            newEnvelopeJson.replace(",\"traceparent\":\"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01\",\"tracestate\":\"vendor=value\",\"futureField\":\"ignored\"", ""),
            EventEnvelope.class
        );

        assertThat(newEnvelope.traceparent()).isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        assertThat(newEnvelope.tracestate()).isEqualTo("vendor=value");
        assertThat(oldEnvelope.traceparent()).isNull();
        assertThat(oldEnvelope.tracestate()).isNull();
    }

    @Test
    void consumerSpanUsesEnvelopeTraceparentAsRemoteParent() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        OpenTelemetry openTelemetry = openTelemetry(exporter);
        Tracer tracer = tracer(openTelemetry);
        Span producerSpan = tracer.spanBuilder("POST /payments").setSpanKind(SpanKind.SERVER).startSpan();
        EventEnvelope envelope;
        try (Scope ignored = producerSpan.makeCurrent()) {
            GlobalOpenTelemetry.set(openTelemetry);
            envelope = factory().create("PaymentCaptured", correlationId(), causationId(), Map.of("paymentIntentId", "pi-1"));
        } finally {
            producerSpan.end();
        }

        try (var ignored = new OtelEventConsumerTracer(openTelemetry, tracer).start("events:payment", "journey-order", envelope)) {
            // closing the scope ends the consumer span
        }

        var consumerSpan = exporter.getFinishedSpanItems().stream()
            .filter(span -> span.getName().equals("PaymentCaptured process"))
            .findFirst()
            .orElseThrow();
        assertThat(consumerSpan.getTraceId()).isEqualTo(producerSpan.getSpanContext().getTraceId());
        assertThat(consumerSpan.getParentSpanContext().isRemote()).isTrue();
        assertThat(consumerSpan.getParentSpanContext().getSpanId()).isEqualTo(producerSpan.getSpanContext().getSpanId());
    }

    @Test
    void redisSubscriberConsumerSpanUsesEnvelopeTraceparentAsRemoteParent() throws Exception {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        OpenTelemetry openTelemetry = openTelemetry(exporter);
        Tracer tracer = tracer(openTelemetry);
        Span producerSpan = tracer.spanBuilder("POST /payments").setSpanKind(SpanKind.SERVER).startSpan();
        EventEnvelope envelope;
        try (Scope ignored = producerSpan.makeCurrent()) {
            GlobalOpenTelemetry.set(openTelemetry);
            envelope = factory().create("PaymentCaptured", correlationId(), causationId(), Map.of("paymentIntentId", "pi-1"));
        } finally {
            producerSpan.end();
        }
        CapturingStreams streams = new CapturingStreams(objectMapper.writeValueAsString(envelope));
        RedisEventSubscriber subscriber = new RedisEventSubscriber(
            streams,
            objectMapper,
            null,
            new InMemoryConsumedEventStore(),
            new OtelEventConsumerTracer(openTelemetry, tracer)
        );
        CountDownLatch handled = new CountDownLatch(1);

        try {
            subscriber.subscribe(List.of("events:payment"), "journey-order", "consumer-1", event -> {
                handled.countDown();
                return HandlerResult.SUCCESS;
            });
            assertThat(handled.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            subscriber.close();
        }

        assertThat(streams.acked).containsExactly("1-0");
        var consumerSpan = exporter.getFinishedSpanItems().stream()
            .filter(span -> span.getName().equals("PaymentCaptured process"))
            .findFirst()
            .orElseThrow();
        assertThat(consumerSpan.getTraceId()).isEqualTo(producerSpan.getSpanContext().getTraceId());
        assertThat(consumerSpan.getParentSpanContext().isRemote()).isTrue();
        assertThat(consumerSpan.getParentSpanContext().getSpanId()).isEqualTo(producerSpan.getSpanContext().getSpanId());
    }

    @Test
    void malformedTraceparentIsIgnoredAndDoesNotAffectConsumerSpanCreation() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        OpenTelemetry openTelemetry = openTelemetry(exporter);
        Tracer tracer = tracer(openTelemetry);
        EventEnvelope envelope = factory()
            .create("PaymentCaptured", correlationId(), causationId(), Map.of("paymentIntentId", "pi-1"))
            .withTraceContext("malformed", null);

        try (var ignored = new OtelEventConsumerTracer(openTelemetry, tracer).start("events:payment", "journey-order", envelope)) {
            // closing the scope ends the consumer span
        }

        var consumerSpan = exporter.getFinishedSpanItems().stream()
            .filter(span -> span.getName().equals("PaymentCaptured process"))
            .findFirst()
            .orElseThrow();
        assertThat(consumerSpan.getParentSpanContext().isValid()).isFalse();
    }

    private static final class CapturingStreams implements RedisStreamOperations {
        private final String envelopeJson;
        private final List<String> acked = new ArrayList<>();
        private boolean delivered;

        private CapturingStreams(String envelopeJson) {
            this.envelopeJson = envelopeJson;
        }

        @Override
        public void createGroup(String stream, String group) {
        }

        @Override
        public String publish(String stream, String envelopeJson) {
            return "1-0";
        }

        @Override
        public synchronized List<StreamEntry> readGroup(String stream, String group, String consumerName) {
            if (delivered) {
                return List.of();
            }
            delivered = true;
            return List.of(new StreamEntry("1-0", envelopeJson));
        }

        @Override
        public List<StreamEntry> autoClaim(String stream, String group, String consumerName) {
            return List.of();
        }

        @Override
        public int deliveryCount(String stream, String group, String messageId) {
            return 1;
        }

        @Override
        public void ack(String stream, String group, String messageId) {
            acked.add(messageId);
        }

        @Override
        public void moveToDlq(String stream, String envelopeJson, DlqMetadata metadata) {
        }
    }

    private static EventEnvelopeFactory factory() {
        return new EventEnvelopeFactory("payment", Clock.fixed(Instant.parse("2026-07-05T10:30:00Z"), ZoneOffset.UTC));
    }

    private static OpenTelemetry openTelemetry(InMemorySpanExporter exporter) {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
        return OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build();
    }

    private static Tracer tracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("trace-test");
    }

    private static String correlationId() {
        return "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c444";
    }

    private static String causationId() {
        return "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c555";
    }
}
