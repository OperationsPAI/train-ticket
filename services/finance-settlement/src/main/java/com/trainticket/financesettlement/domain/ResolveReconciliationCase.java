package com.trainticket.financesettlement.domain;

import java.util.Objects;

public record ResolveReconciliationCase(String reconciliationCaseId, String resolution, String resolutionNote) {
    public ResolveReconciliationCase {
        requireText(reconciliationCaseId, "reconciliationCaseId");
        requireText(resolution, "resolution");
        requireText(resolutionNote, "resolutionNote");
    }
    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank())
            throw new DomainRuleViolation(name + " must not be blank");
    }
}
