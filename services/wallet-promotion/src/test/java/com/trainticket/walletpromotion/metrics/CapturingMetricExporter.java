package com.trainticket.walletpromotion.metrics;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Minimal in-memory {@link MetricExporter} for asserting the OTLP instruments.
 *
 * <p>The shared Java kit gets this from {@code opentelemetry-sdk-testing}, but
 * that artifact is test-scoped to the kit, and adding it here would drag a
 * second OpenTelemetry version onto this service's classpath. The exporter
 * contract is three methods, so the test owns them instead.
 */
public final class CapturingMetricExporter implements MetricExporter {
    private final List<MetricData> exported = new ArrayList<>();

    @Override
    public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
        return AggregationTemporality.CUMULATIVE;
    }

    @Override
    public CompletableResultCode export(Collection<MetricData> metrics) {
        exported.clear();
        exported.addAll(metrics);
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

    public List<MetricData> metrics() {
        return List.copyOf(exported);
    }

    public MetricData metric(String name) {
        return exported.stream()
            .filter(metric -> metric.getName().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "missing " + name + "; exported " + exported.stream().map(MetricData::getName).toList()
            ));
    }
}
