package com.trainticket.walletpromotion.metrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Merges per-currency balance levels into one point per (state, currency).
 *
 * <p>Both repository implementations feed the same accumulator so the in-memory
 * and PostgreSQL paths cannot disagree about units or state mapping.
 */
public final class WalletBalanceAccumulator {
    private final Map<String, long[]> totals = new LinkedHashMap<>();

    public void add(WalletBalanceState state, String currency, long minorUnits) {
        if (currency == null || currency.isBlank()) {
            return;
        }
        long[] byState = totals.computeIfAbsent(currency.trim().toUpperCase(Locale.ROOT), key -> new long[WalletBalanceState.values().length]);
        byState[state.ordinal()] += minorUnits;
    }

    /**
     * Emits every state for every currency seen anywhere: a state that is
     * genuinely zero is a real level, so it is reported as zero rather than
     * omitted (an uninstrumented source stays absent, which is different).
     */
    public List<WalletBalanceSample> samples() {
        List<WalletBalanceSample> samples = new ArrayList<>();
        totals.forEach((currency, byState) -> {
            for (WalletBalanceState state : WalletBalanceState.values()) {
                samples.add(new WalletBalanceSample(state, currency, byState[state.ordinal()]));
            }
        });
        return samples;
    }
}
