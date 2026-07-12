package com.trainticket.financesettlement.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class SettlementView {
    private final String settlementViewId;
    private final String viewType;
    private final int version;
    private final int eventCount;
    private final Instant rebuiltAt;
    private final List<FinanceSettlementEvent> domainEvents;

    private SettlementView(String settlementViewId, String viewType, int version, int eventCount, Instant rebuiltAt) {
        this.settlementViewId = requireText(settlementViewId, "settlementViewId");
        this.viewType = requireText(viewType, "viewType");
        if (version < 1) throw new DomainRuleViolation("view version must be positive");
        this.version = version;
        if (eventCount < 0) throw new DomainRuleViolation("event count must not be negative");
        this.eventCount = eventCount;
        this.rebuiltAt = Objects.requireNonNull(rebuiltAt, "rebuiltAt is required");
        this.domainEvents = new ArrayList<>();
    }

    public static SettlementView rebuild(
        String viewType, int version, int eventCount, Instant rebuiltAt,
        Instant now, String sourceCommandId, String correlationId
    ) {
        SettlementView view = new SettlementView(UUID.randomUUID().toString(), viewType, version, eventCount, rebuiltAt);
        view.domainEvents.add(new SettlementViewRebuilt(
            view.settlementViewId, viewType, eventCount,
            EventMetadata.create(now, sourceCommandId, sourceCommandId, correlationId,
                Map.of("settlementViewId", view.settlementViewId, "viewType", viewType,
                       "version", String.valueOf(version), "eventCount", String.valueOf(eventCount)))
        ));
        return view;
    }

    public String settlementViewId() { return settlementViewId; }
    public String viewType() { return viewType; }
    public int version() { return version; }
    public int eventCount() { return eventCount; }
    public Instant rebuiltAt() { return rebuiltAt; }
    public List<FinanceSettlementEvent> domainEvents() { return Collections.unmodifiableList(domainEvents); }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank())
            throw new DomainRuleViolation(name + " must not be blank");
        return value;
    }
}
