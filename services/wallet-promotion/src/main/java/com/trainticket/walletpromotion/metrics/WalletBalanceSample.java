package com.trainticket.walletpromotion.metrics;

import java.util.Locale;
import java.util.Objects;

/**
 * One balance level in integer minor units, always tied to exactly one currency.
 *
 * <p>PromQL must never sum these across currencies, which is why the currency is
 * a mandatory label rather than an implicit assumption.
 */
public record WalletBalanceSample(WalletBalanceState state, String currency, long minorUnits) {
    public WalletBalanceSample {
        Objects.requireNonNull(state, "state is required");
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency is required");
        }
        currency = currency.trim().toUpperCase(Locale.ROOT);
    }
}
