package com.trainticket.platformkit.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import java.util.Objects;

/** Publishes the event-consumer outcomes under one bounded set of attributes. */
public final class OtelEventConsumerMetrics implements EventConsumerMetrics {
    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("outcome");
    private static final AttributeKey<String> FAILURE_TYPE = AttributeKey.stringKey("failure.type");
    private static final AttributeKey<String> REASON = AttributeKey.stringKey("reason");

    private final LongCounter messages;
    private final LongCounter failures;
    private final LongCounter deadLetters;

    public OtelEventConsumerMetrics(Meter meter) {
        Objects.requireNonNull(meter, "meter is required");
        this.messages = meter.counterBuilder("train_ticket.event.consumer.messages")
            .setDescription("Event deliveries by their processing disposition.")
            .setUnit("{message}")
            .build();
        this.failures = meter.counterBuilder("train_ticket.event.consumer.failures")
            .setDescription("Event-consumer failures by a bounded failure classification.")
            .setUnit("{failure}")
            .build();
        this.deadLetters = meter.counterBuilder("train_ticket.event.consumer.dead_letters")
            .setDescription("Messages successfully handed to a dead-letter stream.")
            .setUnit("{message}")
            .build();
    }

    @Override
    public void recordOutcome(Outcome outcome) {
        messages.add(1, Attributes.of(OUTCOME, outcome.value()));
    }

    @Override
    public void recordFailure(FailureType failureType) {
        failures.add(1, Attributes.of(FAILURE_TYPE, failureType.value()));
    }

    @Override
    public void recordDeadLetter(FailureType reason) {
        deadLetters.add(1, Attributes.of(REASON, reason.value()));
    }
}
