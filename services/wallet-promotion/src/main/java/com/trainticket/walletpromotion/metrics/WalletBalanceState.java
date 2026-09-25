package com.trainticket.walletpromotion.metrics;

/**
 * Closed set of wallet balance states published to Prometheus.
 *
 * <p>The wire contract fixes the label pair at {@code state} and {@code currency}.
 * Adding a value here adds a series, so the set stays deliberately small.
 */
public enum WalletBalanceState {
    AVAILABLE("available"),
    RESERVED("reserved"),
    REDEEMED("redeemed"),
    EXPIRED("expired"),
    REVERSED("reversed");

    private final String value;

    WalletBalanceState(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
