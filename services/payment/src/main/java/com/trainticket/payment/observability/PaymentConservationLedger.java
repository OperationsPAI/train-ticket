package com.trainticket.payment.observability;

import java.util.List;

/**
 * Reads the authoritative conservation totals from durable payment records.
 *
 * <p>The read is a periodic query, not a process counter, so the value survives
 * a restart. A process with no durable payment store returns no rows, which the
 * gauge reports as absent rather than as a zero balance.
 */
public interface PaymentConservationLedger {
    List<CurrencyConservation> totalsByCurrency();

    static PaymentConservationLedger empty() {
        return List::of;
    }
}
