package com.trainticket.postsales.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {
    public Money {
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(currency, "currency is required");
        amount = amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY);
        if (amount.signum() < 0) {
            throw new DomainRuleViolation("money amount must not be negative");
        }
    }

    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), Currency.getInstance(requireText(currencyCode, "currencyCode")));
    }

    public static Money zero(String currencyCode) {
        return zero(Currency.getInstance(requireText(currencyCode, "currencyCode")));
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    /**
     * Cross-context canonical factory: amount in minor units (e.g. cents/fen).
     */
    public static Money fromMinorUnits(long minorUnits, String currencyCode) {
        Currency cur = Currency.getInstance(requireText(currencyCode, "currencyCode"));
        return new Money(BigDecimal.valueOf(minorUnits, cur.getDefaultFractionDigits()), cur);
    }

    /**
     * Cross-context canonical form: amount in minor units (e.g. cents/fen).
     */
    public long toMinorUnits() {
        return amount.movePointRight(currency.getDefaultFractionDigits()).longValue();
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) > 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other money is required");
        if (!currency.equals(other.currency)) {
            throw new DomainRuleViolation("currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
        return value;
    }
}
