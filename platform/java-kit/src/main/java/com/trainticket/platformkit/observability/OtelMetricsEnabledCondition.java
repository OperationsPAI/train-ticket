package com.trainticket.platformkit.observability;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public final class OtelMetricsEnabledCondition extends SpringBootCondition {
    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        if (OtelProperties.metricsEnabled(context.getEnvironment())) {
            return ConditionOutcome.match("OTLP metrics environment is complete");
        }
        return ConditionOutcome.noMatch("OTLP metrics requires otel.metrics.exporter=otlp, otel.exporter.otlp.endpoint, and otel.service.name");
    }
}
