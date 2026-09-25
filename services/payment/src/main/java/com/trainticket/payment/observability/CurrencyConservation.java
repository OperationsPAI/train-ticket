package com.trainticket.payment.observability;

import java.util.Objects;

/**
 * Durable per-currency money totals of the payment domain.
 *
 * <p>The payment domain records captured values on payment-intent snapshots and
 * settled refunds on refund snapshots. {@code reversed} covers settled refunds
 * that return a late collection rather than an ordinary captured payment, so it
 * stays disjoint from {@code refunded} and the invariant has one term per
 * returned minor unit.
 */
public record CurrencyConservation(String currency, long capturedMinorUnits, long refundedMinorUnits, long reversedMinorUnits) {
    public CurrencyConservation {
        Objects.requireNonNull(currency, "currency is required");
        if (capturedMinorUnits < 0 || refundedMinorUnits < 0 || reversedMinorUnits < 0) {
            throw new IllegalArgumentException("payment conservation totals must not be negative");
        }
        if (refundedMinorUnits + reversedMinorUnits > capturedMinorUnits) {
            throw new IllegalArgumentException("refunded and reversed totals cannot exceed captured for " + currency);
        }
    }

    /**
     * The money still held for this currency: captured minus everything already
     * returned. It is derived, never stored, so it cannot drift from its terms.
     */
    public long unsettledMinorUnits() {
        return capturedMinorUnits - refundedMinorUnits - reversedMinorUnits;
    }
}
