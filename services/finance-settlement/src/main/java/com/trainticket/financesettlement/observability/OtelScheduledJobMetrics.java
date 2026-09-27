package com.trainticket.financesettlement.observability;

import com.trainticket.platformkit.observability.OtelMetricsEnabledCondition;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Publishes {@code train_ticket.scheduled_job.success_age} through the process's
 * OpenTelemetry meter.
 *
 * <p>An observable gauge over a map the reporter replaces, so a collection reads
 * the last durable reconstruction rather than a value rebuilt per scrape, and a
 * job that has never succeeded has no point at all.
 */
@Component
@Conditional(OtelMetricsEnabledCondition.class)
public class OtelScheduledJobMetrics implements ScheduledJobMetrics {
    /**
     * The job's name.
     *
     * <p>Not `job`: the OpenTelemetry Prometheus exporter sets `job` as one of its
     * own constant labels from the target's service name, and a datapoint
     * attribute with the same name makes it refuse the whole metric --
     * measured, `failed to convert metric
     * train_ticket.scheduled_job.success_age: duplicate label names in constant
     * and variable labels`.
     */
    private static final AttributeKey<String> SCHEDULED_JOB = AttributeKey.stringKey("scheduled_job");

    private final Map<String, Long> successAgeSeconds = new ConcurrentHashMap<>();

    @Autowired
    public OtelScheduledJobMetrics(OpenTelemetry openTelemetry, Environment environment) {
        this(openTelemetry.getMeter(environment.getProperty("otel.service.name", "finance-settlement")));
    }

    public OtelScheduledJobMetrics(Meter meter) {
        Meter required = Objects.requireNonNull(meter, "meter is required");
        required.gaugeBuilder("train_ticket.scheduled_job.success_age")
            .ofLongs()
            .setDescription("Seconds since a scheduled job last succeeded, rebuilt from durable run records.")
            .setUnit("s")
            .buildWithCallback(measurement -> successAgeSeconds.forEach((job, seconds) ->
                measurement.record(seconds, io.opentelemetry.api.common.Attributes.of(SCHEDULED_JOB, job))));
    }

    @Override
    public void successAge(String job, Duration age) {
        if (job == null || job.isBlank() || age == null || age.isNegative()) {
            return;
        }
        successAgeSeconds.put(job, age.toSeconds());
    }
}
