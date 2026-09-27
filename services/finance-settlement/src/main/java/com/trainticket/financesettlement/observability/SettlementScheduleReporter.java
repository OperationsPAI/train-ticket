package com.trainticket.financesettlement.observability;

import com.trainticket.financesettlement.application.SettlementScheduleRunRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Publishes how long ago each scheduled job last succeeded.
 *
 * <p>The durable run records are the source: this service's jobs write
 * `settlement_schedule_runs` on every attempt, and nothing else reads it back.
 * Reporting the age of the last success is what would have made the scheduler's
 * silent absence visible -- the bean was skipped by an unreliable
 * `@ConditionalOnBean`, so neither job ran for ten hours and the platform
 * published nothing at all about them.
 *
 * <p>It runs on its own daemon thread with a startup delay, like the payment
 * conservation refresh: a rebuild reads a small indexed table, but it must not
 * compete with the scheduler's own lease queries, which run every minute.
 */
@Component
@Conditional(ScheduledJobReporterCondition.class)
public class SettlementScheduleReporter {
    private static final Logger LOGGER = LoggerFactory.getLogger(SettlementScheduleReporter.class);

    private final SettlementScheduleRunRepository runs;
    private final ScheduledJobMetrics metrics;
    private final Clock clock;
    private final long initialDelayMillis;
    private final long refreshIntervalMillis;
    private final ScheduledExecutorService refresher = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "finance-scheduled-job-refresh");
        thread.setDaemon(true);
        return thread;
    });

    public SettlementScheduleReporter(SettlementScheduleRunRepository runs, ScheduledJobMetrics metrics, Clock clock,
                                      @Value("${finance.scheduled-job-initial-delay-ms:30000}") long initialDelayMillis,
                                      @Value("${finance.scheduled-job-refresh-ms:60000}") long refreshIntervalMillis) {
        this.runs = runs;
        this.metrics = metrics;
        this.clock = clock;
        this.initialDelayMillis = initialDelayMillis;
        this.refreshIntervalMillis = refreshIntervalMillis;
    }

    @PostConstruct
    void start() {
        refresher.scheduleWithFixedDelay(this::refresh, initialDelayMillis, refreshIntervalMillis, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        refresher.shutdownNow();
    }

    void refresh() {
        Instant now = clock.instant();
        Map<String, Instant> lastSuccess;
        try {
            lastSuccess = runs.lastSuccessByJobType();
        } catch (RuntimeException failure) {
            // A failed read keeps the previous reading: "the reporter could not
            // read the runs" and "no job has ever succeeded" must not read the
            // same, and the gauge is rebuilt from durable state on the next tick.
            LOGGER.warn("scheduled job refresh failed; keeping the previous reading", failure);
            return;
        }
        for (Map.Entry<String, Instant> entry : lastSuccess.entrySet()) {
            Duration age = Duration.between(entry.getValue(), now);
            metrics.successAge(entry.getKey(), age.isNegative() ? Duration.ZERO : age);
        }
    }
}
