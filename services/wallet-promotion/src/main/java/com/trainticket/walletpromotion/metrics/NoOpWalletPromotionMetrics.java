package com.trainticket.walletpromotion.metrics;

/** The default when OTLP metrics are not configured. */
final class NoOpWalletPromotionMetrics implements WalletPromotionMetrics {
    @Override
    public void operation(Operation operation, Outcome outcome, ReasonCode reasonCode, String currency) {
        // Intentionally empty: metrics are opt-in per process.
    }
}
