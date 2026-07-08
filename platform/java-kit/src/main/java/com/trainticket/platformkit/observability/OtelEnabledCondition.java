package com.trainticket.platformkit.observability;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public final class OtelEnabledCondition extends SpringBootCondition {
    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        if (OtelProperties.tracesEnabled(context.getEnvironment())) {
            return ConditionOutcome.match("OTLP tracing environment is complete");
        }
        return ConditionOutcome.noMatch("OTLP tracing requires otel.traces.exporter=otlp, otel.exporter.otlp.endpoint, and otel.service.name");
    }
}
