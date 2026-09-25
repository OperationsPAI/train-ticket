package com.trainticket.payment.observability;

import java.util.Objects;

/**
 * One {@code (state, currency)} point of the payment conservation gauge.
 *
 * <p>{@code state} is one of {@code captured}, {@code refunded},
 * {@code reversed} or {@code unsettled}; the amount is always integer minor
 * units of the point's own currency. A point never mixes currencies.
 */
public record PaymentConservationPoint(String state, String currency, long minorUnits) {
    public static final String CAPTURED = "captured";
    public static final String REFUNDED = "refunded";
    public static final String REVERSED = "reversed";
    public static final String UNSETTLED = "unsettled";

    public PaymentConservationPoint {
        Objects.requireNonNull(state, "state is required");
        Objects.requireNonNull(currency, "currency is required");
        switch (state) {
            case CAPTURED, REFUNDED, REVERSED, UNSETTLED -> {
            }
            default -> throw new IllegalArgumentException("unsupported payment conservation state: " + state);
        }
        if (minorUnits < 0) {
            throw new IllegalArgumentException("payment conservation amount must not be negative");
        }
    }
}
