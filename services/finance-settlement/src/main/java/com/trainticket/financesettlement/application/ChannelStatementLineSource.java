package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.Money;
import java.util.List;

/**
 * The channel's own lines for a statement, read in pages.
 *
 * `ChannelStatementLineMatched` had a consumer and no producer: nothing
 * published it, so `channel_statement_lines` stayed empty and the daily
 * reconciliation could only compare a day's captures against one
 * statement-level total. A statement is 342,648 lines on the measured
 * channel-day, and neither side should fan that out one event at a time, so the
 * channel side is a paged read of the statement the platform already stores:
 * payment-channel serves it by statement line ordinal with a next cursor.
 *
 * The read is deliberately an interface here rather than an HTTP client, so the
 * reconciliation is testable without a live payment-channel and so the shape of
 * the pull is the service's decision rather than the adapter's.
 */
public interface ChannelStatementLineSource {

    /** One page of the statement's lines, starting after the given ordinal. */
    Page page(String channelStatementId, long after, int limit);

    /** The most lines one request returns; the channel clamps to the same 500. */
    int MAX_PAGE_SIZE = 500;

    record Page(List<Line> lines, Long nextCursor, int lineCount) {
        public Page {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /**
     * A statement line reduced to what the reconciliation needs.
     *
     * `LineType` is kept because a statement carries both payments and refunds
     * and the two are signed differently in the channel's gross totals.
     */
    record Line(
        String statementLineId,
        String lineType,
        String channelOrderId,
        String channelRefundId,
        String paymentIntentId,
        String refundId,
        Money actualAmount
    ) {
        public Line {
            statementLineId = statementLineId == null ? "" : statementLineId;
            lineType = lineType == null ? "" : lineType;
            channelOrderId = channelOrderId == null ? "" : channelOrderId;
            channelRefundId = channelRefundId == null ? "" : channelRefundId;
            paymentIntentId = paymentIntentId == null ? "" : paymentIntentId;
            refundId = refundId == null ? "" : refundId;
            actualAmount = actualAmount == null ? Money.zero(java.util.Currency.getInstance("CNY")) : actualAmount;
        }
    }
}
