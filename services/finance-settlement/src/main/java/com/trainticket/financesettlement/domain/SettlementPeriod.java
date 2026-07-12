package com.trainticket.financesettlement.domain;

import java.time.LocalDate;
import java.util.Objects;

public record SettlementPeriod(LocalDate startDate, LocalDate endDate, SettlementFrequency frequency) {
    public SettlementPeriod {
        Objects.requireNonNull(startDate, "startDate is required");
        Objects.requireNonNull(endDate, "endDate is required");
        Objects.requireNonNull(frequency, "frequency is required");
        if (endDate.isBefore(startDate)) {
            throw new DomainRuleViolation("settlement period endDate must not be before startDate");
        }
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(startDate) && !date.isAfter(endDate);
    }
}
