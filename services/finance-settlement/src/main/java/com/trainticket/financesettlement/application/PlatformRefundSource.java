package com.trainticket.financesettlement.application;

import java.time.LocalDate;
import java.util.List;

/**
 * The refunds the platform settled on a settlement date, read in pages.
 *
 * <p>The channel's statement carries refund lines, each naming the platform
 * refund it settles; payment owns the other side and answers for a window in
 * one indexed read. Without it the daily reconciliation could only compare the
 * money that came in, and a day's refunds were never checked against the
 * channel's own record of paying them back.
 */
public interface PlatformRefundSource {

    /** One page of the date's settled refunds, following {@code cursor} when given. */
    Page page(LocalDate settlementDate, String cursor, int limit);

    /** The most refunds one request returns; payment clamps to the same 1000. */
    int MAX_PAGE_SIZE = 1000;

    record Page(List<Refund> refunds, String nextCursor) {
        public Page {
            refunds = refunds == null ? List.of() : List.copyOf(refunds);
        }
    }

    record Refund(
        String refundId,
        String paymentIntentId,
        String businessReference,
        String sourceCaseRef,
        String currency,
        long amountMinorUnits
    ) {
        public Refund {
            refundId = refundId == null ? "" : refundId;
            paymentIntentId = paymentIntentId == null ? "" : paymentIntentId;
            businessReference = businessReference == null ? "" : businessReference;
            sourceCaseRef = sourceCaseRef == null ? "" : sourceCaseRef;
            currency = currency == null || currency.isBlank() ? "CNY" : currency;
        }

        public com.trainticket.financesettlement.domain.Money amount() {
            return com.trainticket.financesettlement.domain.Money.of(currency,
                java.math.BigDecimal.valueOf(amountMinorUnits, java.util.Currency.getInstance(currency).getDefaultFractionDigits()).toPlainString());
        }
    }
}
