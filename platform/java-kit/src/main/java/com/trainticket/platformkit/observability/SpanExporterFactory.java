package com.trainticket.platformkit.observability;

import io.opentelemetry.sdk.trace.export.SpanExporter;

@FunctionalInterface
public interface SpanExporterFactory {
    SpanExporter create(String endpoint);
}
