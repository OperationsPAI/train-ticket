package com.trainticket.payment.domain.ports;

import java.time.Instant;
import java.util.List;

/**
 * The refunds that have actually settled, as a reading for settlement.
 *
 * <p>A settlement date's reconciliation compares the channel's refund lines with
 * the platform's own refunds, and the channel line carries the refund id. The id
 * and its settled instant otherwise live inside the refund snapshot's domain
 * events, where a date range cannot be read without walking every snapshot, so
 * this is the same fact in one small indexed table -- written when a refund
 * settles and backfilled by migration 013 from the events already stored.
 */
public interface RefundSettlementIndex {
    void record(RefundSettlement settlement);

    /**
     * One page of settled refunds, ordered by settlement instant then refund id.
     *
     * <p>{@code after} is the previous page's {@link Cursor}; the order and the
     * cursor are the same pair, so paging is a range scan on the index rather
     * than a sort per page.
     */
    List<RefundSettlement> list(Instant settledFrom, Instant settledTo, Cursor after, int limit);

    /** A refund the platform settled. */
    record RefundSettlement(
        String refundId,
        String paymentIntentId,
        String businessReference,
        String sourceCaseRef,
        String currency,
        long amountMinorUnits,
        Instant settledAt
    ) {}

    /** The position of the last row a page returned. */
    record Cursor(Instant settledAt, String refundId) {
        public String encode() {
            return settledAt.toEpochMilli() + "|" + refundId;
        }

        public static Cursor decode(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            int separator = raw.indexOf('|');
            if (separator <= 0) {
                return null;
            }
            return new Cursor(Instant.ofEpochMilli(Long.parseLong(raw.substring(0, separator))), raw.substring(separator + 1));
        }
    }
}
