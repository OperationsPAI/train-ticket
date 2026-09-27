package com.trainticket.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trainticket.payment.domain.ports.RefundSettlementIndex;
import com.trainticket.payment.infrastructure.persistence.InMemoryLatePaymentCaseRepository;
import com.trainticket.payment.infrastructure.persistence.InMemoryPaymentIntentRepository;
import com.trainticket.payment.infrastructure.persistence.InMemoryRefundRepository;
import com.trainticket.payment.infrastructure.persistence.InMemoryRefundSettlementIndex;
import com.trainticket.payment.infrastructure.persistence.InMemoryReservationPaymentRequestRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The settlement index is what a settlement date's refund reconciliation reads.
 *
 * <p>It answers a day's refunds ordered by settlement instant and refund id, so
 * the caller pages with a cursor instead of sorting a day per page.
 */
class RefundSettlementIndexTest {
    private static final Instant DAY = Instant.parse("2026-07-02T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-03T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void pagesBySettlementInstantAndRefundId() {
        InMemoryRefundSettlementIndex index = new InMemoryRefundSettlementIndex();
        index.record(settlement("rf-3", DAY.plusSeconds(300)));
        index.record(settlement("rf-1", DAY.plusSeconds(60)));
        index.record(settlement("rf-2", DAY.plusSeconds(60)));
        index.record(settlement("rf-4", DAY.plusSeconds(86_400)));
        PaymentCommandService service = service(index);

        Map<String, Object> first = service.refundSettlements(DAY, DAY.plusSeconds(86_400), null, 2);
        assertEquals(List.of("rf-1", "rf-2"), ids(first));
        RefundSettlementIndex.Cursor cursor = RefundSettlementIndex.Cursor.decode((String) first.get("nextCursor"));

        Map<String, Object> second = service.refundSettlements(DAY, DAY.plusSeconds(86_400), cursor, 2);
        assertEquals(List.of("rf-3"), ids(second), "the cursor resumes after the last row of the page");
        assertTrue(second.get("nextCursor") == null, "a short page ends the sequence");
    }

    @Test
    void aProcessWithoutAnIndexAnswersAnEmptyPageRatherThanFailing() {
        PaymentCommandService service = service(null);

        Map<String, Object> page = service.refundSettlements(DAY, DAY.plusSeconds(86_400), null, 500);

        assertEquals(List.of(), page.get("items"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> ids(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("items")).stream()
            .map(item -> (String) item.get("refundId"))
            .toList();
    }

    private static RefundSettlementIndex.RefundSettlement settlement(String refundId, Instant settledAt) {
        return new RefundSettlementIndex.RefundSettlement(refundId, "pi-" + refundId, "ord-1", "psc-1", "CNY", 1234L, settledAt);
    }

    private static PaymentCommandService service(RefundSettlementIndex index) {
        PaymentCommandService service = new PaymentCommandService(
            CLOCK,
            envelope -> {
            },
            new InMemoryPaymentIntentRepository(),
            new InMemoryRefundRepository(),
            new InMemoryReservationPaymentRequestRepository(),
            new InMemoryLatePaymentCaseRepository()
        );
        service.setRefundSettlementIndex(index);
        return service;
    }
}
