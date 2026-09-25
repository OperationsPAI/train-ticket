package com.trainticket.platformkit.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** OpenTelemetry instruments for one process's transactional outbox relay. */
public final class OtelOutboxMetrics implements OutboxMetrics {
    private static final AttributeKey<String> STAGE = AttributeKey.stringKey("stage");

    private final AtomicLong unpublishedSample = new AtomicLong();
    private final AtomicLong oldestUnpublishedAgeMillis = new AtomicLong();
    private final LongCounter published;
    private final LongCounter failures;

    public OtelOutboxMetrics(Meter meter) {
        Objects.requireNonNull(meter, "meter is required");
        meter.gaugeBuilder("train_ticket.outbox.unpublished.sample")
            .ofLongs()
            .setDescription("Rows visible in the relay's bounded unpublished head sample.")
            .setUnit("{event}")
            .buildWithCallback(measurement -> measurement.record(unpublishedSample.get()));
        meter.gaugeBuilder("train_ticket.outbox.oldest_unpublished.age")
            .setDescription("Age of the head-of-line unpublished outbox event.")
            .setUnit("s")
            .buildWithCallback(measurement -> measurement.record(oldestUnpublishedAgeMillis.get() / 1000.0));
        this.published = meter.counterBuilder("train_ticket.outbox.published")
            .setDescription("Outbox events whose bounded Redis publish pipeline completed successfully.")
            .setUnit("{event}")
            .build();
        this.failures = meter.counterBuilder("train_ticket.outbox.failures")
            .setDescription("Outbox relay failures by a bounded processing stage.")
            .setUnit("{failure}")
            .build();
    }

    @Override
    public void observeUnpublishedSample(int rows, Duration oldestAge) {
        unpublishedSample.set(Math.max(rows, 0));
        oldestUnpublishedAgeMillis.set(Math.max(oldestAge.toMillis(), 0L));
    }

    @Override
    public void recordPublished(int rows) {
        if (rows > 0) {
            published.add(rows);
        }
    }

    @Override
    public void recordFailure(FailureStage stage) {
        failures.add(1, Attributes.of(STAGE, stage.value()));
    }
}
