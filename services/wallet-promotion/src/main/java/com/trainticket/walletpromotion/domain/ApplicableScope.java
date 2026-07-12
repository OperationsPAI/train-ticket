package com.trainticket.walletpromotion.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplicableScope(String scopeType, List<String> referenceIds, String currency) {
    public ApplicableScope {
        if (scopeType == null || scopeType.isBlank()) throw new DomainException("scopeType is required");
        if (currency == null || currency.isBlank()) throw new DomainException("scope currency is required");
        currency = currency.toUpperCase();
        referenceIds = referenceIds == null ? null : List.copyOf(referenceIds);
    }
}
