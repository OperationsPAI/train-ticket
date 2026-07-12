package com.trainticket.financesettlement.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

public record ReconciliationReport(
    String batchId,
    String settlementDate,
    int totalEntries,
    int matchedEntries,
    BigDecimal matchRate,
    Money totalVariance,
    List<ReconciliationEntry> exceptions
) {
    public ReconciliationReport {
        requireText(batchId, "batchId");
        requireText(settlementDate, "settlementDate");
        if (totalEntries < 0 || matchedEntries < 0 || matchedEntries > totalEntries) {
            throw new DomainRuleViolation("invalid reconciliation report counts");
        }
        Objects.requireNonNull(matchRate, "matchRate is required");
        Objects.requireNonNull(totalVariance, "totalVariance is required");
        exceptions = List.copyOf(exceptions == null ? List.of() : exceptions);
    }

    static ReconciliationReport from(String batchId, String settlementDate, List<ReconciliationEntry> entries, Currency currency) {
        int total = entries.size();
        int matched = (int) entries.stream().filter(entry -> entry.status() == ReconciliationStatus.MATCHED).count();
        BigDecimal rate = total == 0
            ? BigDecimal.ONE.setScale(4)
            : BigDecimal.valueOf(matched).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
        Money variance = entries.stream()
            .map(ReconciliationEntry::variance)
            .reduce(Money.zero(currency), Money::plus);
        return new ReconciliationReport(
            batchId,
            settlementDate,
            total,
            matched,
            rate,
            variance,
            entries.stream().filter(ReconciliationEntry::isException).toList()
        );
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
        return value;
    }
}
