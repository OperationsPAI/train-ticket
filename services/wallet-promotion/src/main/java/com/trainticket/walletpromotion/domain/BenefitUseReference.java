package com.trainticket.walletpromotion.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashSet;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record BenefitUseReference(String orderId, String postSalesCaseId, List<String> segmentRefs) {
    public BenefitUseReference {
        orderId = requireText(orderId, "useReference.orderId");
        postSalesCaseId = blankToNull(postSalesCaseId);
        segmentRefs = segmentRefs == null
            ? List.of()
            : List.copyOf(new LinkedHashSet<>(segmentRefs.stream()
                .map(value -> requireText(value, "useReference.segmentRefs"))
                .toList()));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new DomainException(name + " is required");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
