package com.trainticket.journeyorder.observability;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * The recorder used when no OTLP metrics endpoint is configured.
 *
 * <p>Absent instrumentation is unknown, not zero, so this deliberately publishes nothing rather
 * than a zero series that would read as "no order was attempted".
 */
@Component
@ConditionalOnMissingBean(value = OrderFunnelMetrics.class, ignored = NoopOrderFunnelMetrics.class)
public class NoopOrderFunnelMetrics implements OrderFunnelMetrics {
    @Override
    public void transition(Stage stage, Outcome outcome, Channel channel) {
        // No metrics endpoint is configured for this process.
    }
}
