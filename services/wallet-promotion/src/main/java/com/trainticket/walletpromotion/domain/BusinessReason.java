package com.trainticket.walletpromotion.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record BusinessReason(ReasonType reasonType, String reasonCode, String referenceType, String referenceId, String description) {
    public BusinessReason {
        Objects.requireNonNull(reasonType, "reasonType is required");
        if (reasonCode == null || reasonCode.isBlank()) throw new DomainException("reasonCode is required");
        reasonCode = reasonCode.trim();
        referenceType = blankToNull(referenceType);
        referenceId = blankToNull(referenceId);
        description = blankToNull(description);
    }
    public String key() { return reasonType + "|" + reasonCode + "|" + nullToEmpty(referenceType) + "|" + nullToEmpty(referenceId); }
    private static String blankToNull(String v) { return v == null || v.isBlank() ? null : v.trim(); }
    private static String nullToEmpty(String v) { return v == null ? "" : v; }
}
