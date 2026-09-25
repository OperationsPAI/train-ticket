package com.trainticket.bookingorchestration.observability;

import com.trainticket.platformkit.observability.OtelMetricsEnabledCondition;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Publishes the three booking saga instruments over the process's existing OTLP meter.
 *
 * <ul>
 *   <li>{@code train_ticket.booking.saga.active} is an UpDownCounter. The reporter hands it the
 *       durable level, and this recorder applies only the difference from the last level it
 *       reported, so a saga that leaves a stage decrements exactly once and a restart re-seeds from
 *       stored state instead of from a zeroed process counter.</li>
 *   <li>{@code train_ticket.booking.saga.oldest_age} is an observable gauge in seconds, read from
 *       the last durable reconstruction.</li>
 *   <li>{@code train_ticket.booking.saga.transitions} is a counter of bounded outcomes.</li>
 * </ul>
 */
@Component
@Conditional(OtelMetricsEnabledCondition.class)
public class OtelBookingSagaMetrics implements BookingSagaMetrics {
    private static final AttributeKey<String> STAGE = AttributeKey.stringKey("stage");
    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("outcome");
    private static final AttributeKey<String> REASON_CODE = AttributeKey.stringKey("reason_code");

    private final LongUpDownCounter active;
    private final LongCounter transitions;
    private final Map<Stage, AtomicLong> reportedActive = new ConcurrentHashMap<>();
    private final Map<Stage, AtomicLong> oldestAgeMillis = new ConcurrentHashMap<>();
    private final Object activeLock = new Object();

    @Autowired
    public OtelBookingSagaMetrics(OpenTelemetry openTelemetry, Environment environment) {
        this(openTelemetry.getMeter(environment.getProperty("otel.service.name", "booking-orchestration")));
    }

    public OtelBookingSagaMetrics(Meter meter) {
        Meter required = Objects.requireNonNull(meter, "meter is required");
        this.active = required.upDownCounterBuilder("train_ticket.booking.saga.active")
            .setDescription("Booking sagas currently waiting at a stage, rebuilt from durable state.")
            .setUnit("{saga}")
            .build();
        this.transitions = required.counterBuilder("train_ticket.booking.saga.transitions")
            .setDescription("Booking saga transition outcomes by stage and bounded reason.")
            .setUnit("{transition}")
            .build();
        for (Stage stage : Stage.values()) {
            oldestAgeMillis.put(stage, new AtomicLong());
        }
        required.gaugeBuilder("train_ticket.booking.saga.oldest_age")
            .setDescription("Age of the oldest nonterminal booking saga at a stage, rebuilt from durable state.")
            .setUnit("s")
            .buildWithCallback(measurement -> {
                for (Map.Entry<Stage, AtomicLong> entry : oldestAgeMillis.entrySet()) {
                    measurement.record(
                        Math.max(entry.getValue().get(), 0L) / 1000.0,
                        Attributes.of(STAGE, entry.getKey().value()));
                }
            });
    }

    @Override
    public void trackActive(Stage stage, long activeSagas) {
        if (activeSagas < 0) {
            throw new IllegalArgumentException("active saga count must not be negative");
        }
        synchronized (activeLock) {
            long previous = reportedActive.computeIfAbsent(stage, ignored -> new AtomicLong()).getAndSet(activeSagas);
            long delta = activeSagas - previous;
            if (delta != 0) {
                active.add(delta, Attributes.of(STAGE, stage.value()));
            }
        }
    }

    @Override
    public void observeOldestAge(Stage stage, Duration oldestAge) {
        Objects.requireNonNull(oldestAge, "oldestAge is required");
        oldestAgeMillis.computeIfAbsent(stage, ignored -> new AtomicLong())
            .set(Math.max(oldestAge.toMillis(), 0L));
    }

    @Override
    public void transition(Stage stage, Outcome outcome, ReasonCode reasonCode) {
        transitions.add(1, Attributes.of(
            STAGE, stage.value(),
            OUTCOME, outcome.value(),
            REASON_CODE, reasonCode.value()));
    }
}
