package com.trainticket.financesettlement.domain;

import java.util.Objects;

public record RebuildSettlementView(String viewType, int version, int eventCount) {
    public RebuildSettlementView {
        requireText(viewType, "viewType");
        if (version < 1) throw new DomainRuleViolation("view version must be positive");
        if (eventCount < 0) throw new DomainRuleViolation("event count must not be negative");
    }
    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank())
            throw new DomainRuleViolation(name + " must not be blank");
    }
}
