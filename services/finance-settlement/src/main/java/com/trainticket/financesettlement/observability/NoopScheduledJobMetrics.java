package com.trainticket.financesettlement.observability;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * The recorder used when no OTLP metrics endpoint is configured.
 *
 * <p>Publishes nothing: absent instrumentation is unknown, not a job that just
 * succeeded.
 */
@Component
@ConditionalOnMissingBean(value = ScheduledJobMetrics.class, ignored = NoopScheduledJobMetrics.class)
public class NoopScheduledJobMetrics implements ScheduledJobMetrics {
    @Override
    public void successAge(String job, Duration age) {
        // No metrics endpoint is configured for this process.
    }
}
