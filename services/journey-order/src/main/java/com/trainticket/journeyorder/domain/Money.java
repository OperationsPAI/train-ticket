package com.trainticket.journeyorder.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Minor domain money type for Journey Order commercial summaries.
 * It deliberately models order commercial value only; it is not a payment-channel balance.
 */
public record Money(Currency currency, BigDecimal amount) implements Comparable<Money> {
    public Money {
        Objects.requireNonNull(currency, "currency is required");
        Objects.requireNonNull(amount, "amount is required");
        amount = amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY);
    }

    public static Money of(String currencyCode, String amount) {
        return new Money(Currency.getInstance(currencyCode), new BigDecimal(amount));
    }

    public static Money zero(Currency currency) {
        return new Money(currency, BigDecimal.ZERO.setScale(currency.getDefaultFractionDigits()));
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(currency, amount.add(other.amount));
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(currency, amount.subtract(other.amount));
    }

    public Money negate() {
        return new Money(currency, amount.negate());
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other money is required");
        if (!currency.equals(other.currency)) {
            throw new DomainRuleViolation("money currency mismatch: " + currency + " vs " + other.currency);
        }
    }
}
