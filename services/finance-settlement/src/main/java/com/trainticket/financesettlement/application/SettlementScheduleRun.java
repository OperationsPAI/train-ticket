package com.trainticket.financesettlement.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record SettlementScheduleRun(
    String jobKey,
    String jobType,
    LocalDate startDate,
    LocalDate endDate,
    String status,
    String leaseOwner,
    Instant leaseUntil,
    int attempts,
    List<String> resultRefs,
    String failure,
    Instant startedAt,
    Instant completedAt,
    Instant updatedAt
) {
    public SettlementScheduleRun {
        resultRefs = resultRefs == null ? List.of() : List.copyOf(resultRefs);
    }
}
