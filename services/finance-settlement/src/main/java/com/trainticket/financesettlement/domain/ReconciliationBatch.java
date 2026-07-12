package com.trainticket.financesettlement.domain;

import com.trainticket.platformkit.idempotency.UuidV7;
import com.trainticket.platformkit.messaging.PrefixedIds;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ReconciliationBatch {
    private final String batchId;
    private final LocalDate settlementDate;
    private final Instant cutoffAt;
    private final Currency currency;
    private final List<ReconciliationEntry> entries;
    private final List<FinanceSettlementEvent> domainEvents;
    private ReconciliationReport report;
    private long version;

    private ReconciliationBatch(String batchId, LocalDate settlementDate, Instant cutoffAt, Currency currency, List<ReconciliationEntry> entries) {
        this.batchId = requireText(batchId, "batchId");
        this.settlementDate = Objects.requireNonNull(settlementDate, "settlementDate is required");
        this.cutoffAt = Objects.requireNonNull(cutoffAt, "cutoffAt is required");
        this.currency = Objects.requireNonNull(currency, "currency is required");
        this.entries = new ArrayList<>(List.copyOf(entries));
        this.domainEvents = new ArrayList<>();
        this.report = ReconciliationReport.from(batchId, settlementDate.toString(), this.entries, currency);
    }

    public static ReconciliationBatch complete(
        LocalDate settlementDate,
        Instant cutoffAt,
        Currency currency,
        List<ReconciliationEntry> entries,
        Instant now,
        String sourceCommandId,
        String causationId,
        String correlationId
    ) {
        ReconciliationBatch batch = new ReconciliationBatch("rb-" + UuidV7.generate(), settlementDate, cutoffAt, currency, entries);
        batch.domainEvents.add(batch.completedEvent(now, sourceCommandId, causationId, correlationId));
        return batch;
    }

    public static ReconciliationBatch rehydrate(String batchId, LocalDate settlementDate, Instant cutoffAt, Currency currency, List<ReconciliationEntry> entries) {
        return new ReconciliationBatch(batchId, settlementDate, cutoffAt, currency, entries);
    }

    public ReconciliationBatch withVersion(long version) {
        if (version < 0) {
            throw new DomainRuleViolation("version must not be negative");
        }
        this.version = version;
        return this;
    }

    private ReconciliationCompleted completedEvent(Instant now, String sourceCommandId, String causationId, String correlationId) {
        return ReconciliationCompleted.forBatch(
            batchId,
            settlementDate.toString(),
            report.totalEntries(),
            report.matchedEntries(),
            report.matchRate(),
            report.totalVariance(),
            report.exceptions().size(),
            EventMetadata.create(
                now,
                sourceCommandId,
                causationId,
                correlationId,
                Map.of("batchId", batchId, "settlementDate", settlementDate.toString())
            )
        );
    }

    public Map<String, Long> statusCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ReconciliationStatus status : ReconciliationStatus.values()) {
            counts.put(status.name(), entries.stream().filter(entry -> entry.status() == status).count());
        }
        return Collections.unmodifiableMap(counts);
    }

    public String batchId() { return batchId; }
    public LocalDate settlementDate() { return settlementDate; }
    public Instant cutoffAt() { return cutoffAt; }
    public Currency currency() { return currency; }
    public List<ReconciliationEntry> entries() { return Collections.unmodifiableList(entries); }
    public ReconciliationReport report() { return report; }
    public List<FinanceSettlementEvent> domainEvents() { return Collections.unmodifiableList(domainEvents); }
    public long version() { return version; }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
        return value;
    }
}
