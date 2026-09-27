package com.trainticket.financesettlement.observability;

import java.time.Duration;

/**
 * Whether this service's scheduled jobs are running, and how stale their work is.
 *
 * <p>A sweep that stops is invisible: the daily reconciliation and the supplier
 * settlement write their results and nothing else, so before this the only
 * evidence a period had been processed was the result itself. That is exactly how
 * `@ConditionalOnBean(DataSource.class)` on the scheduler bean skipped it for ten
 * hours unnoticed -- the platform published nothing about the job at all.
 */
public interface ScheduledJobMetrics {
    /**
     * Seconds since the job last succeeded.
     *
     * <p>A job that has never succeeded publishes nothing rather than zero: "the
     * job has not run yet" and "the job ran and is up to date" must not read the
     * same, which is the same rule the durable gauges follow.
     */
    void successAge(String job, Duration age);
}
