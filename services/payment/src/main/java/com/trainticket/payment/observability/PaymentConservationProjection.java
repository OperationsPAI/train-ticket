package com.trainticket.payment.observability;

import java.util.ArrayList;
import java.util.List;

/**
 * Projects durable per-currency totals onto the four conservation states.
 *
 * <p>The projection is deliberately a pure function over
 * {@link CurrencyConservation}: the same totals produce the same points on any
 * process, which is what makes the restart-reconstruction test meaningful. It
 * also asserts the conservation invariant for every currency before emitting,
 * because a gauge that reports an inconsistent balance is worse than one that
 * reports nothing.
 */
public final class PaymentConservationProjection {
    private PaymentConservationProjection() {
    }

    public static List<PaymentConservationPoint> project(List<CurrencyConservation> totals) {
        List<PaymentConservationPoint> points = new ArrayList<>();
        for (CurrencyConservation total : totals) {
            long unsettled = total.unsettledMinorUnits();
            if (total.capturedMinorUnits() != total.refundedMinorUnits() + total.reversedMinorUnits() + unsettled) {
                throw new IllegalStateException(
                    "payment conservation invariant violated for " + total.currency());
            }
            points.add(new PaymentConservationPoint(PaymentConservationPoint.CAPTURED, total.currency(), total.capturedMinorUnits()));
            points.add(new PaymentConservationPoint(PaymentConservationPoint.REFUNDED, total.currency(), total.refundedMinorUnits()));
            points.add(new PaymentConservationPoint(PaymentConservationPoint.REVERSED, total.currency(), total.reversedMinorUnits()));
            points.add(new PaymentConservationPoint(PaymentConservationPoint.UNSETTLED, total.currency(), unsettled));
        }
        return List.copyOf(points);
    }
}
