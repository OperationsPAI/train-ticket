package com.trainticket.marketingcampaign.domain;

import java.util.Objects;

public record Money(String currency, long minorUnits) {
    public Money {
        if (currency == null || currency.isBlank()) throw new DomainException("currency is required");
        currency = currency.toUpperCase();
    }
    public static Money zero(String currency) { return new Money(currency, 0); }
    public void requirePositive(String field) { if (minorUnits <= 0) throw new DomainException(field + " must be positive"); }
    public void requireNonNegative(String field) { if (minorUnits < 0) throw new DomainException(field + " must not be negative"); }
    public void requireSameCurrency(Money other) { if (!currency.equals(Objects.requireNonNull(other).currency())) throw new DomainException("currency mismatch"); }
    public Money plus(Money other) { requireSameCurrency(other); return new Money(currency, minorUnits + other.minorUnits); }
    public Money minus(Money other) { requireSameCurrency(other); return new Money(currency, minorUnits - other.minorUnits); }
}
