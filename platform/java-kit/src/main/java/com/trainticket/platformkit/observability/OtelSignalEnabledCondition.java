package com.trainticket.platformkit.observability;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when either signal is configured for OTLP export.
 *
 * The SDK object that carries the providers is shared, so it must exist when
 * only one of the two signals is selected. Gating it on traces alone left a
 * metrics-only service with providers and nothing to publish them through.
 */
public final class OtelSignalEnabledCondition extends SpringBootCondition {
    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        boolean traces = OtelProperties.tracesEnabled(context.getEnvironment());
        boolean metrics = OtelProperties.metricsEnabled(context.getEnvironment());
        if (traces || metrics) {
            return ConditionOutcome.match("OTLP export is enabled for traces=" + traces + " metrics=" + metrics);
        }
        return ConditionOutcome.noMatch("OTLP export requires otel.traces.exporter=otlp or otel.metrics.exporter=otlp");
    }
}
