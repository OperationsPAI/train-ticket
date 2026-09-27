package com.trainticket.financesettlement.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trainticket.financesettlement.domain.Money;
import com.trainticket.financesettlement.domain.ReconciliationBatch;
import com.trainticket.financesettlement.domain.ReconciliationEntry;
import com.trainticket.financesettlement.domain.ReconciliationStatus;
import com.trainticket.platformkit.messaging.EventEnvelope;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The channel side of a settlement date is read, not published.
 *
 * `ChannelStatementLineMatched` had a consumer and no producer, so
 * `channel_statement_lines` stayed empty and the day's captures could only be
 * compared with one statement-level total -- which reports every capture as
 * missing in channel. These tests hold the read, its paging, and the reason the
 * order reference matters: a line carries the payment intent, and the
 * reconciliation joins on the order.
 */
class ChannelStatementIngestTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-03T00:05:00Z"), ZoneOffset.UTC);
    private static final LocalDate SETTLEMENT_DATE = LocalDate.parse("2026-07-02");

    @Test
    void readsEveryPageAndMatchesCapturesLineByLine() {
        InMemoryFinanceSettlementProjectionRepository projections = projections();
        projections.saveCapture("ord-1", capture("ord-1", "pi-1", "10.00"));
        projections.saveCapture("ord-2", capture("ord-2", "pi-2", "20.00"));
        projections.saveChannelStatement(statement());
        // One line a page, so the cursor path is what this case exercises.
        PagedSource source = new PagedSource(1, List.of(
            new ChannelStatementLineSource.Line("line-1", "PAYMENT", "cho-1", "", "pi-1", "", Money.of("CNY", "10.00")),
            new ChannelStatementLineSource.Line("line-2", "PAYMENT", "cho-2", "", "pi-2", "", Money.of("CNY", "19.00"))
        ));

        ReconciliationBatch batch = service(projections, source, intents()).runDailyReconciliation(SETTLEMENT_DATE, "corr-ingest-1");

        assertEquals(2, source.calls, "both pages were read");
        assertEquals(2, batch.entries().size(), "one entry per reconciliation key, not one per statement");
        // The entry is keyed by the payment intent -- the one reference the
        // platform's capture and the channel's line both carry.
        Map<String, ReconciliationEntry> byIntent = new LinkedHashMap<>();
        batch.entries().forEach(entry -> byIntent.put(entry.paymentIntentId(), entry));
        assertEquals(ReconciliationStatus.MATCHED, byIntent.get("pi-1").status());
        assertEquals(Money.of("CNY", "0.00"), byIntent.get("pi-1").variance());
        assertEquals(ReconciliationStatus.AMOUNT_MISMATCH, byIntent.get("pi-2").status(),
            "the channel paid 19.00 against a captured 20.00");
        assertEquals(Money.of("CNY", "1.00"), byIntent.get("pi-2").variance());
        assertTrue(byIntent.get("pi-1").sourceEventIds().contains("line-1"));
    }

    @Test
    void theChannelsRefundsAreComparedWithThePlatformsOwnSettlement() {
        InMemoryFinanceSettlementProjectionRepository projections = projections();
        projections.saveChannelStatement(statement());
        projections.saveChannelStatementLines(List.of(
            new ChannelStatementLineProjection("line-r1", "chs-1", SETTLEMENT_DATE.toString(), "ord-1", "pi-1", "cho-1", Money.of("CNY", "10.00"), "line-r1", ChannelStatementLineProjection.REFUND, "rf-1"),
            new ChannelStatementLineProjection("line-r2", "chs-1", SETTLEMENT_DATE.toString(), "ord-2", "pi-2", "cho-2", Money.of("CNY", "19.00"), "line-r2", ChannelStatementLineProjection.REFUND, "rf-2"),
            new ChannelStatementLineProjection("line-r3", "chs-1", SETTLEMENT_DATE.toString(), "ord-3", "pi-3", "cho-3", Money.of("CNY", "5.00"), "line-r3", ChannelStatementLineProjection.REFUND, "rf-3")
        ));
        PlatformRefundSource refunds = new FixedRefunds(List.of(
            new PlatformRefundSource.Refund("rf-1", "pi-1", "ord-1", "psc-1", "CNY", 1000),
            new PlatformRefundSource.Refund("rf-2", "pi-2", "ord-2", "psc-2", "CNY", 2000),
            new PlatformRefundSource.Refund("rf-4", "pi-4", "ord-4", "psc-4", "CNY", 700)
        ));

        ReconciliationBatch batch = service(projections, null, intents()).withPlatformRefunds(refunds)
            .runDailyReconciliation(SETTLEMENT_DATE, "corr-refunds");

        Map<String, ReconciliationEntry> byRefund = new LinkedHashMap<>();
        batch.entries().forEach(entry -> byRefund.put(entry.entryId(), entry));
        assertEquals(ReconciliationStatus.MATCHED, byRefund.get("re-refund-rf-1").status(), "the channel paid back what the platform settled");
        assertEquals(ReconciliationStatus.AMOUNT_MISMATCH, byRefund.get("re-refund-rf-2").status(), "20.00 settled against 19.00 paid back");
        assertEquals(Money.of("CNY", "1.00"), byRefund.get("re-refund-rf-2").variance());
        assertEquals(ReconciliationStatus.CHANNEL_ONLY, byRefund.get("re-refund-rf-3").status(), "the channel paid a refund the platform has no settlement for");
        assertEquals(ReconciliationStatus.PLATFORM_ONLY, byRefund.get("re-refund-rf-4").status(), "the platform settled a refund the channel has no line for");
    }

    @Test
    void withoutAPlatformRefundSourceTheRefundSideIsNotInvented() {
        InMemoryFinanceSettlementProjectionRepository projections = projections();
        projections.saveChannelStatement(statement());
        projections.saveChannelStatementLine(new ChannelStatementLineProjection(
            "line-r1", "chs-1", SETTLEMENT_DATE.toString(), "ord-1", "pi-1", "cho-1", Money.of("CNY", "10.00"), "line-r1",
            ChannelStatementLineProjection.REFUND, "rf-1"));

        ReconciliationBatch batch = service(projections, null, intents()).runDailyReconciliation(SETTLEMENT_DATE, "corr-refunds-2");

        assertTrue(batch.entries().stream().noneMatch(entry -> entry.entryId().startsWith("re-refund-")),
            "without a platform side the refund lines stay the channel's own record: " + batch.entries());
    }

    /** Serves every refund in one page. */
    private static final class FixedRefunds implements PlatformRefundSource {
        private final List<Refund> refunds;

        private FixedRefunds(List<Refund> refunds) {
            this.refunds = refunds;
        }

        @Override
        public Page page(java.time.LocalDate settlementDate, String cursor, int limit) {
            return new Page(refunds, null);
        }
    }

    @Test
    void anOrderPaidTwiceIsReconciledPaymentByPayment() {
        // The platform attributes each capture to the business reference it was
        // for -- the order for a fare, the ancillary item for an add-on -- while
        // the channel only knows the payment intent. Keying on the business
        // reference therefore merged the order's payments on one side and not on
        // the other: measured on 2026-09-26, 56,884 of 431,530 entries were
        // mismatches and every one of them was an order that had paid twice.
        InMemoryFinanceSettlementProjectionRepository projections = projections();
        projections.saveCapture("ord-1", capture("ord-1", "pi-1", "10.00"));
        projections.saveCapture("aoi-1", capture("aoi-1", "pi-2", "20.00"));
        projections.saveChannelStatement(statement());
        PagedSource source = new PagedSource(500, List.of(
            new ChannelStatementLineSource.Line("line-1", "PAYMENT", "cho-1", "", "pi-1", "", Money.of("CNY", "10.00")),
            new ChannelStatementLineSource.Line("line-2", "PAYMENT", "cho-2", "", "pi-2", "", Money.of("CNY", "20.00"))
        ));
        PaymentIntentOrderReferenceRepository intents = new PaymentIntentOrderReferenceRepository() {
            @Override
            public void save(String paymentIntentId, String orderReference) {
            }

            @Override
            public Optional<String> findOrderReference(String paymentIntentId) {
                return Optional.of("ord-1");
            }
        };

        ReconciliationBatch batch = service(projections, source, intents).runDailyReconciliation(SETTLEMENT_DATE, "corr-ingest-5");

        assertEquals(2, batch.entries().size(), "one entry per payment");
        assertTrue(batch.entries().stream().allMatch(entry -> entry.status() == ReconciliationStatus.MATCHED),
            "both payments of the order matched their own capture: " + batch.entries());
        assertEquals(Money.of("CNY", "0.00"), batch.report().totalVariance());
    }

    @Test
    void aStatementWhoseLinesAreAlreadyRecordedIsNotReadAgain() {
        InMemoryFinanceSettlementProjectionRepository projections = projections();
        projections.saveCapture("ord-1", capture("ord-1", "pi-1", "10.00"));
        projections.saveChannelStatement(statement());
        PagedSource source = new PagedSource(500, List.of(
            new ChannelStatementLineSource.Line("line-1", "PAYMENT", "cho-1", "", "pi-1", "", Money.of("CNY", "10.00"))
        ));
        projections.saveChannelStatementLine(new ChannelStatementLineProjection(
            "line-1", "chs-1", SETTLEMENT_DATE.toString(), "ord-1", "pi-1", "cho-1", Money.of("CNY", "10.00"), "line-1"));

        service(projections, source, intents()).ingestChannelStatementLines(SETTLEMENT_DATE, "corr-ingest-2");

        assertEquals(0, source.calls, "the channel side was already read");
    }

    @Test
    void withoutASourceTheStatementLevelFallbackStays() {
        InMemoryFinanceSettlementProjectionRepository projections = projections();
        projections.saveCapture("ord-1", capture("ord-1", "pi-1", "10.00"));
        projections.saveChannelStatement(statement());

        ReconciliationBatch batch = service(projections, null, intents()).runDailyReconciliation(SETTLEMENT_DATE, "corr-ingest-3");

        assertTrue(batch.entries().stream().allMatch(entry -> entry.status() != ReconciliationStatus.MATCHED));
        assertEquals(2, batch.entries().size(), "one capture plus the statement's own aggregate");
        assertTrue(batch.entries().stream().anyMatch(entry -> entry.status() == ReconciliationStatus.CHANNEL_ONLY),
            "the statement-level fallback is what is left when there is no line source");
    }

    @Test
    void anEmptyStatementLeavesTheFallbackInPlace() {
        InMemoryFinanceSettlementProjectionRepository projections = projections();
        projections.saveCapture("ord-1", capture("ord-1", "pi-1", "10.00"));
        projections.saveChannelStatement(statement());
        PagedSource source = new PagedSource(500, List.of());

        ReconciliationBatch batch = service(projections, source, intents()).runDailyReconciliation(SETTLEMENT_DATE, "corr-ingest-4");

        assertFalse(projections.hasChannelStatementLines("chs-1"));
        assertEquals(2, batch.entries().size());
    }

    private static InMemoryFinanceSettlementProjectionRepository projections() {
        return new InMemoryFinanceSettlementProjectionRepository();
    }

    private static FinanceSettlementEventHandler.PaymentCaptureFact capture(String orderId, String paymentIntentId, String amount) {
        return new FinanceSettlementEventHandler.PaymentCaptureFact(
            orderId, paymentIntentId, Money.of("CNY", amount), "evt-" + orderId, SETTLEMENT_DATE.atStartOfDay().toInstant(ZoneOffset.UTC).plusSeconds(60));
    }

    private static ChannelStatementProjection statement() {
        return new ChannelStatementProjection(
            "chs-1", "ALIPAY_SIM", SETTLEMENT_DATE.toString(), "CNY", "v1", 2,
            Money.of("CNY", "29.00"), Money.of("CNY", "0.00"), Money.of("CNY", "0.00"),
            "sha256:abc", "FROZEN", SETTLEMENT_DATE.atStartOfDay().toInstant(ZoneOffset.UTC), null, "evt-statement");
    }

    private static PaymentIntentOrderReferenceRepository intents() {
        Map<String, String> orders = Map.of("pi-1", "ord-1", "pi-2", "ord-2");
        return new PaymentIntentOrderReferenceRepository() {
            @Override
            public void save(String paymentIntentId, String orderReference) {
            }

            @Override
            public Optional<String> findOrderReference(String paymentIntentId) {
                return Optional.ofNullable(orders.get(paymentIntentId));
            }
        };
    }

    private static FinanceSettlementApplicationService service(
        FinanceSettlementProjectionRepository projections,
        ChannelStatementLineSource source,
        PaymentIntentOrderReferenceRepository references
    ) {
        FinanceSettlementApplicationService service = new FinanceSettlementApplicationService(
            new InMemoryRevenueRecognitionRepository(),
            new InMemoryReconciliationCaseRepository(),
            new InMemoryInvoiceRepository(),
            projections,
            new InMemoryReconciliationBatchRepository(),
            new InMemorySupplierSettlementRepository(),
            new InMemoryFeeAccrualRepository(),
            new RecordingPublisher(),
            new DomainEventEnvelopeMapper(),
            CLOCK
        ).withPaymentIntentOrderReferences(references);
        return source == null ? service : service.withChannelStatementLines(source);
    }

    /** Serves the lines in slices of {@code pageSize}, with a cursor while more remain. */
    private static final class PagedSource implements ChannelStatementLineSource {
        private final List<Line> lines;
        private final int pageSize;
        private int calls;

        private PagedSource(int pageSize, List<Line> lines) {
            this.pageSize = pageSize;
            this.lines = lines;
        }

        @Override
        public Page page(String channelStatementId, long after, int limit) {
            calls++;
            if (after >= lines.size()) return new Page(List.of(), null, lines.size());
            int from = (int) after;
            int to = Math.min(from + pageSize, lines.size());
            List<Line> slice = new ArrayList<>(lines.subList(from, to));
            Long next = to < lines.size() ? (long) to : null;
            return new Page(slice, next, lines.size());
        }
    }

    private static final class RecordingPublisher implements EventPublisher {
        private final List<EventEnvelope> published = new ArrayList<>();

        @Override
        public void publish(EventEnvelope envelope) {
            published.add(envelope);
        }
    }
}
