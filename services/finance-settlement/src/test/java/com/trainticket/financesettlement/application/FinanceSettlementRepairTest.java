package com.trainticket.financesettlement.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trainticket.financesettlement.domain.FeeAccrual;
import com.trainticket.financesettlement.domain.Money;
import com.trainticket.financesettlement.domain.ReconciliationBatch;
import com.trainticket.financesettlement.domain.ReconciliationStatus;
import com.trainticket.financesettlement.domain.RevenueRecognition;
import com.trainticket.financesettlement.domain.SettlementFrequency;
import com.trainticket.financesettlement.domain.SupplierSettlement;
import com.trainticket.platformkit.messaging.EventEnvelope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;

class FinanceSettlementRepairTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-03T00:05:00Z"), ZoneOffset.UTC);

    @Test
    void dailyReconciliationUsesSettlementDateAndOrderPaymentKey() {
        InMemoryFinanceSettlementProjectionRepository projections = new InMemoryFinanceSettlementProjectionRepository();
        projections.saveCapture("ord-1", new FinanceSettlementEventHandler.PaymentCaptureFact(
            "ord-1", "pi-1", Money.of("CNY", "10.00"), "evt-cap-1", Instant.parse("2026-07-02T10:00:00Z")));
        projections.saveCapture("ord-2", new FinanceSettlementEventHandler.PaymentCaptureFact(
            "ord-2", "pi-2", Money.of("CNY", "20.00"), "evt-cap-2", Instant.parse("2026-07-01T10:00:00Z")));
        projections.saveChannelStatementLine(new ChannelStatementLineProjection(
            "line-1", "stmt-1", "2026-07-02", "ord-1", "pi-1", "cho-1", Money.of("CNY", "10.00"), "evt-line-1"));
        projections.saveChannelStatementLine(new ChannelStatementLineProjection(
            "line-2", "stmt-2", "2026-07-01", "ord-2", "pi-2", "cho-2", Money.of("CNY", "20.00"), "evt-line-2"));

        ReconciliationBatch batch = service(new InMemoryRevenueRecognitionRepository(), projections, new InMemoryFeeAccrualRepository(), new RecordingPublisher())
            .runDailyReconciliation(LocalDate.parse("2026-07-02"), "corr-1");

        assertEquals(1, batch.entries().size());
        assertEquals("ord-1", batch.entries().getFirst().orderId());
        assertEquals(ReconciliationStatus.MATCHED, batch.entries().getFirst().status());
        assertEquals(Money.of("CNY", "0.00"), batch.report().totalVariance());
    }

    @Test
    void supplierSettlementUsesSupplierAgreementReferenceAndPeriod() {
        InMemoryRevenueRecognitionRepository recognitions = new InMemoryRevenueRecognitionRepository();
        recognitions.save(recognize("ord-1", "sup-1", "100.00", "2026-07-02T08:00:00Z"));
        recognitions.save(recognize("ord-2", "sup-2", "200.00", "2026-07-02T08:00:00Z"));
        recognitions.save(recognize("ord-3", "sup-1", "300.00", "2026-07-01T08:00:00Z"));

        SupplierSettlement settlement = service(recognitions, new InMemoryFinanceSettlementProjectionRepository(), new InMemoryFeeAccrualRepository(), new RecordingPublisher())
            .calculateSupplierSettlement("sup-1", LocalDate.parse("2026-07-02"), LocalDate.parse("2026-07-02"), SettlementFrequency.DAILY,
                new BigDecimal("0.05"), BigDecimal.ZERO, "corr-1");

        assertEquals(1, settlement.allocations().size());
        assertEquals(Money.of("CNY", "100.00"), settlement.grossRevenue());
        assertEquals(Money.of("CNY", "5.00"), settlement.platformCommission());
        assertEquals(Money.of("CNY", "95.00"), settlement.supplierPayable());
    }

    @Test
    void feeAccrualIsPersistedAndPublishedForApplicationFlow() {
        InMemoryFeeAccrualRepository feeAccruals = new InMemoryFeeAccrualRepository();
        RecordingPublisher publisher = new RecordingPublisher();
        FinanceSettlementApplicationService service = service(new InMemoryRevenueRecognitionRepository(), new InMemoryFinanceSettlementProjectionRepository(), feeAccruals, publisher);

        FeeAccrual accrual = service.accrueFeesForPaymentCapture(
            "ord-1", Money.of("CNY", "100.00"), Money.of("CNY", "0.00"), "evt-payment", CLOCK.instant(), "evt-payment", "corr-1");

        assertEquals(List.of(accrual), feeAccruals.findByOrderId("ord-1"));
        assertEquals(Money.of("CNY", "2.00"), accrual.platformServiceFee());
        assertEquals(Money.of("CNY", "1.00"), accrual.supplierServiceFee());
        assertEquals(Money.of("CNY", "0.18"), accrual.taxCalculation().vatOnServiceFees());
        assertTrue(publisher.published.stream().anyMatch(envelope -> envelope.eventType().equals("FeeAccrued")));
    }

    private static RevenueRecognition recognize(String orderId, String supplierId, String amount, String recognizedAt) {
        return RevenueRecognition.recognize(
            orderId, supplierId, "fare", Money.of("CNY", amount), "policy-v1", "evt-" + orderId,
            Instant.parse(recognizedAt), CLOCK.instant(), "cmd-1", "corr-1");
    }

    private static FinanceSettlementApplicationService service(
        RevenueRecognitionRepository recognitions,
        FinanceSettlementProjectionRepository projections,
        FeeAccrualRepository feeAccruals,
        EventPublisher publisher
    ) {
        return new FinanceSettlementApplicationService(
            recognitions,
            new InMemoryReconciliationCaseRepository(),
            new InMemoryInvoiceRepository(),
            projections,
            new InMemoryReconciliationBatchRepository(),
            new InMemorySupplierSettlementRepository(),
            feeAccruals,
            publisher,
            new DomainEventEnvelopeMapper(),
            CLOCK
        );
    }

    private static final class RecordingPublisher implements EventPublisher {
        private final List<EventEnvelope> published = new ArrayList<>();

        @Override
        public void publish(EventEnvelope envelope) {
            published.add(envelope);
        }
    }
}
