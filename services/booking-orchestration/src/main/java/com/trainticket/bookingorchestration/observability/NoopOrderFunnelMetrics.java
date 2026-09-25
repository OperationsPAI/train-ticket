package com.trainticket.bookingorchestration.observability;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * The recorder used when no OTLP metrics endpoint is configured.
 *
 * <p>It publishes nothing rather than a zero series: absent instrumentation is unknown, not a
 * healthy funnel.
 */
@Component
@ConditionalOnMissingBean(value = OrderFunnelMetrics.class, ignored = NoopOrderFunnelMetrics.class)
public class NoopOrderFunnelMetrics implements OrderFunnelMetrics {
    @Override
    public void transition(Stage stage, Outcome outcome, Channel channel) {
        // No metrics endpoint is configured for this process.
    }
}
