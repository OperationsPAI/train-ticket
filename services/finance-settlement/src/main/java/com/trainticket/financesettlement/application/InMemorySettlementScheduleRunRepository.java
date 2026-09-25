package com.trainticket.financesettlement.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class InMemorySettlementScheduleRunRepository implements SettlementScheduleRunRepository {
    private final Map<String, SettlementScheduleRun> runs = new LinkedHashMap<>();

    @Override
    public synchronized Optional<SettlementScheduleRun> acquire(String jobKey, String jobType, LocalDate startDate, LocalDate endDate,
                                                                String owner, Instant now, Duration leaseDuration) {
        SettlementScheduleRun existing = runs.get(jobKey);
        if (existing != null && ("SUCCEEDED".equals(existing.status()) || existing.leaseUntil().isAfter(now))) {
            return Optional.empty();
        }
        int attempts = existing == null ? 1 : existing.attempts() + 1;
        Instant startedAt = existing == null ? now : existing.startedAt();
        SettlementScheduleRun acquired = new SettlementScheduleRun(jobKey, jobType, startDate, endDate, "RUNNING", owner,
            now.plus(leaseDuration), attempts, List.of(), null, startedAt, null, now);
        runs.put(jobKey, acquired);
        return Optional.of(acquired);
    }

    @Override
    public synchronized void complete(String jobKey, String owner, List<String> resultRefs, Instant completedAt) {
        SettlementScheduleRun run = owned(jobKey, owner);
        runs.put(jobKey, new SettlementScheduleRun(run.jobKey(), run.jobType(), run.startDate(), run.endDate(), "SUCCEEDED",
            owner, completedAt, run.attempts(), resultRefs, null, run.startedAt(), completedAt, completedAt));
    }

    @Override
    public synchronized void fail(String jobKey, String owner, String failure, Instant failedAt) {
        SettlementScheduleRun run = owned(jobKey, owner);
        runs.put(jobKey, new SettlementScheduleRun(run.jobKey(), run.jobType(), run.startDate(), run.endDate(), "FAILED",
            owner, failedAt, run.attempts(), List.of(), failure, run.startedAt(), failedAt, failedAt));
    }

    @Override
    public synchronized Optional<SettlementScheduleRun> findByKey(String jobKey) {
        return Optional.ofNullable(runs.get(jobKey));
    }

    @Override
    public synchronized List<SettlementScheduleRun> find(int limit, int offset) {
        return runs.values().stream().sorted(Comparator.comparing(SettlementScheduleRun::updatedAt).reversed())
            .skip(offset).limit(limit).toList();
    }

    @Override
    public synchronized long count() { return runs.size(); }

    private SettlementScheduleRun owned(String jobKey, String owner) {
        SettlementScheduleRun run = runs.get(jobKey);
        if (run == null || !owner.equals(run.leaseOwner()) || !"RUNNING".equals(run.status())) {
            throw new IllegalStateException("settlement schedule lease is not owned");
        }
        return run;
    }
}
