package com.trainticket.platformkit.observability;

import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;

public final class OtlpSpanExporterFactory implements SpanExporterFactory {
    @Override
    public SpanExporter create(String endpoint) {
        return OtlpGrpcSpanExporter.builder()
            .setEndpoint(endpoint)
            .build();
    }
}
