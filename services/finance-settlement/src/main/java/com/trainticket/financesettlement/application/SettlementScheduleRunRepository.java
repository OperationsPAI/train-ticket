package com.trainticket.financesettlement.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SettlementScheduleRunRepository {
    Optional<SettlementScheduleRun> acquire(String jobKey, String jobType, LocalDate startDate, LocalDate endDate,
                                            String owner, Instant now, Duration leaseDuration);
    void complete(String jobKey, String owner, List<String> resultRefs, Instant completedAt);
    void fail(String jobKey, String owner, String failure, Instant failedAt);
    Optional<SettlementScheduleRun> findByKey(String jobKey);
    List<SettlementScheduleRun> find(int limit, int offset);
    long count();
}
