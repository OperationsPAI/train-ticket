package com.trainticket.bookingorchestration.observability;

import com.trainticket.platformkit.observability.OtelMetricsEnabledCondition;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Publishes {@code train_ticket.order.transitions} over the process's existing OTLP meter.
 *
 * <p>The unit is a dimension-less transition count; a reader takes {@code rate()} over it. The
 * labels are the closed stage, outcome and channel vocabularies shared with journey-order.
 */
@Component
@Conditional(OtelMetricsEnabledCondition.class)
public class OtelOrderFunnelMetrics implements OrderFunnelMetrics {
    private static final AttributeKey<String> STAGE = AttributeKey.stringKey("stage");
    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("outcome");
    private static final AttributeKey<String> CHANNEL = AttributeKey.stringKey("channel");

    private final LongCounter transitions;

    @Autowired
    public OtelOrderFunnelMetrics(OpenTelemetry openTelemetry, Environment environment) {
        this(openTelemetry.getMeter(environment.getProperty("otel.service.name", "booking-orchestration")));
    }

    public OtelOrderFunnelMetrics(Meter meter) {
        this.transitions = Objects.requireNonNull(meter, "meter is required")
            .counterBuilder("train_ticket.order.transitions")
            .setDescription("Order funnel transitions by stage, outcome and purchase channel.")
            .setUnit("{transition}")
            .build();
    }

    @Override
    public void transition(Stage stage, Outcome outcome, Channel channel) {
        transitions.add(1, Attributes.of(
            STAGE, stage.value(),
            OUTCOME, outcome.value(),
            CHANNEL, channel.value()));
    }
}
