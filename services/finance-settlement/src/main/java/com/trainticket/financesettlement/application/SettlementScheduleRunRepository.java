package com.trainticket.financesettlement.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface SettlementScheduleRunRepository {
    Optional<SettlementScheduleRun> acquire(String jobKey, String jobType, LocalDate startDate, LocalDate endDate,
                                            String owner, Instant now, Duration leaseDuration);
    void complete(String jobKey, String owner, List<String> resultRefs, Instant completedAt);
    void fail(String jobKey, String owner, String failure, Instant failedAt);
    Optional<SettlementScheduleRun> findByKey(String jobKey);
    List<SettlementScheduleRun> find(int limit, int offset);
    long count();

    /**
     * The most recent successful completion of each job type.
     *
     * <p>For the scheduled-job gauge: a job that has never succeeded is absent
     * from the map rather than present with a zero age, so "has not run" and
     * "ran and is up to date" do not read the same.
     */
    default Map<String, Instant> lastSuccessByJobType() {
        return Map.of();
    }
}
