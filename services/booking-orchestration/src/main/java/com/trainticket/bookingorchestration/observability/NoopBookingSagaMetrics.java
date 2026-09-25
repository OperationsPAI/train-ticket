package com.trainticket.bookingorchestration.observability;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * The recorder used when no OTLP metrics endpoint is configured.
 *
 * <p>It publishes nothing rather than a zero series: absent instrumentation is unknown, not a
 * healthy saga population.
 */
@Component
@ConditionalOnMissingBean(value = BookingSagaMetrics.class, ignored = NoopBookingSagaMetrics.class)
public class NoopBookingSagaMetrics implements BookingSagaMetrics {
    @Override
    public void trackActive(Stage stage, long activeSagas) {
        // No metrics endpoint is configured for this process.
    }

    @Override
    public void observeOldestAge(Stage stage, Duration oldestAge) {
        // No metrics endpoint is configured for this process.
    }

    @Override
    public void transition(Stage stage, Outcome outcome, ReasonCode reasonCode) {
        // No metrics endpoint is configured for this process.
    }
}
