package com.trainticket.payment.infrastructure.persistence;

import com.trainticket.payment.domain.ports.RefundSettlementIndex;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The settlement index a payment process without a database keeps. */
public final class InMemoryRefundSettlementIndex implements RefundSettlementIndex {
    private final Map<String, RefundSettlement> byRefundId = new LinkedHashMap<>();

    @Override
    public synchronized void record(RefundSettlement settlement) {
        byRefundId.putIfAbsent(settlement.refundId(), settlement);
    }

    @Override
    public synchronized List<RefundSettlement> list(Instant settledFrom, Instant settledTo, Cursor after, int limit) {
        List<RefundSettlement> page = new ArrayList<>();
        byRefundId.values().stream()
            .filter(settlement -> !settlement.settledAt().isBefore(settledFrom) && settlement.settledAt().isBefore(settledTo))
            .filter(settlement -> after == null || isAfterCursor(settlement, after))
            .sorted(Comparator.comparing(RefundSettlement::settledAt).thenComparing(RefundSettlement::refundId))
            .limit(limit)
            .forEach(page::add);
        return page;
    }

    private static boolean isAfterCursor(RefundSettlement settlement, Cursor cursor) {
        int byInstant = settlement.settledAt().compareTo(cursor.settledAt());
        return byInstant > 0 || (byInstant == 0 && settlement.refundId().compareTo(cursor.refundId()) > 0);
    }
}
