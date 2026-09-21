package com.trainticket.platformkit.observability;

import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.sdk.metrics.export.MetricExporter;

public final class OtlpMetricExporterFactory implements MetricExporterFactory {
    @Override
    public MetricExporter create(String endpoint) {
        return OtlpGrpcMetricExporter.builder()
            .setEndpoint(endpoint)
            .build();
    }
}
