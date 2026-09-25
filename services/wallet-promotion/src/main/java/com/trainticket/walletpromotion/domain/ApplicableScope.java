package com.trainticket.walletpromotion.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplicableScope(BenefitScopeType scopeType, List<String> referenceIds, String currency) {
    public ApplicableScope {
        Objects.requireNonNull(scopeType, "scopeType is required");
        if (currency == null || currency.isBlank()) throw new DomainException("scope currency is required");
        currency = currency.toUpperCase();
        referenceIds = referenceIds == null
            ? List.of()
            : List.copyOf(new LinkedHashSet<>(referenceIds.stream().map(ApplicableScope::requireReference).toList()));
        if (scopeType == BenefitScopeType.ANY_TRIP && !referenceIds.isEmpty()) {
            throw new DomainException("ANY_TRIP scope must not carry referenceIds");
        }
        if (scopeType != BenefitScopeType.ANY_TRIP && referenceIds.isEmpty()) {
            throw new DomainException(scopeType + " scope requires referenceIds");
        }
    }

    private static String requireReference(String value) {
        if (value == null || value.isBlank()) throw new DomainException("scope referenceIds must not be blank");
        return value.trim();
    }
}
