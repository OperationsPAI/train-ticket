package com.trainticket.platformkit.observability;

import io.opentelemetry.sdk.metrics.export.MetricExporter;

@FunctionalInterface
public interface MetricExporterFactory {
    MetricExporter create(String endpoint);
}
