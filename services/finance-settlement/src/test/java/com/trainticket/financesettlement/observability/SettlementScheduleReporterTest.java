package com.trainticket.financesettlement.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trainticket.financesettlement.application.SettlementScheduleRunRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The scheduled-job gauge is what makes a job that stopped running visible.
 *
 * <p>This service's scheduler was skipped for ten hours by an unreliable
 * `@ConditionalOnBean` and nothing published anything about it: the daily
 * reconciliation and the supplier settlement write their results and nothing
 * else.
 */
class SettlementScheduleReporterTest {
    private static final Instant NOW = Instant.parse("2026-09-25T06:00:00Z");

    @Test
    void reportsTheAgeOfEachJobsLastSuccess() {
        Map<String, Instant> lastSuccess = new LinkedHashMap<>();
        lastSuccess.put("DAILY_RECONCILIATION", NOW.minusSeconds(90));
        lastSuccess.put("SUPPLIER_SETTLEMENT", NOW.minusSeconds(3600));
        RecordingMetrics metrics = new RecordingMetrics();
        SettlementScheduleReporter reporter = new SettlementScheduleReporter(
            new StubRuns(lastSuccess, false), metrics, Clock.fixed(NOW, ZoneOffset.UTC), 0, 60_000);

        reporter.refresh();

        assertEquals(List.of("DAILY_RECONCILIATION", "SUPPLIER_SETTLEMENT"), metrics.jobs);
        assertEquals(List.of(Duration.ofSeconds(90), Duration.ofHours(1)), metrics.ages);
    }

    @Test
    void aFailedReadKeepsThePreviousReadingInsteadOfPublishingZero() {
        RecordingMetrics metrics = new RecordingMetrics();
        SettlementScheduleReporter reporter = new SettlementScheduleReporter(
            new StubRuns(Map.of(), true), metrics, Clock.fixed(NOW, ZoneOffset.UTC), 0, 60_000);

        reporter.refresh();

        // "The reporter could not read the runs" and "no job has ever succeeded"
        // must not produce the same series.
        assertTrue(metrics.jobs.isEmpty());
    }

    private static final class RecordingMetrics implements ScheduledJobMetrics {
        private final List<String> jobs = new ArrayList<>();
        private final List<Duration> ages = new ArrayList<>();

        @Override
        public void successAge(String job, Duration age) {
            jobs.add(job);
            ages.add(age);
        }
    }

    private record StubRuns(Map<String, Instant> lastSuccess, boolean failRead) implements SettlementScheduleRunRepository {
        @Override
        public Map<String, Instant> lastSuccessByJobType() {
            if (failRead) throw new IllegalStateException("database unavailable");
            return lastSuccess;
        }

        @Override
        public Optional<com.trainticket.financesettlement.application.SettlementScheduleRun> acquire(
                String jobKey, String jobType, LocalDate startDate, LocalDate endDate, String owner, Instant now, Duration leaseDuration) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void complete(String jobKey, String owner, List<String> resultRefs, Instant completedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void fail(String jobKey, String owner, String failure, Instant failedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<com.trainticket.financesettlement.application.SettlementScheduleRun> findByKey(String jobKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<com.trainticket.financesettlement.application.SettlementScheduleRun> find(int limit, int offset) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long count() {
            throw new UnsupportedOperationException();
        }
    }
}
