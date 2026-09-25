package com.trainticket.financesettlement.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trainticket.financesettlement.domain.Money;
import com.trainticket.financesettlement.domain.RevenueRecognition;
import com.trainticket.financesettlement.domain.SettlementFrequency;
import com.trainticket.financesettlement.domain.SettlementPeriod;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FinanceSettlementSchedulerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-25T06:00:00Z"), ZoneOffset.UTC);

    @Test
    void calculatesLatestClosedPeriods() {
        LocalDate today = LocalDate.parse("2026-09-25");
        assertEquals(new SettlementPeriod(LocalDate.parse("2026-09-24"), LocalDate.parse("2026-09-24"), SettlementFrequency.DAILY),
            FinanceSettlementScheduler.closedPeriod(today, SettlementFrequency.DAILY));
        assertEquals(new SettlementPeriod(LocalDate.parse("2026-09-14"), LocalDate.parse("2026-09-20"), SettlementFrequency.WEEKLY),
            FinanceSettlementScheduler.closedPeriod(today, SettlementFrequency.WEEKLY));
        assertEquals(new SettlementPeriod(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"), SettlementFrequency.MONTHLY),
            FinanceSettlementScheduler.closedPeriod(today, SettlementFrequency.MONTHLY));
    }

    @Test
    void persistsReconciliationAndSupplierResultsExactlyOnce() {
        InMemoryRevenueRecognitionRepository recognitions = new InMemoryRevenueRecognitionRepository();
        recognitions.save(recognition("ord-1", "sup-1", "fare", "100.00"));
        recognitions.save(recognition("ord-2", "ord-2", "fare", "50.00"));
        recognitions.save(recognition("ord-3", "pi-change", "change-payment", "20.00"));
        RecordingPublisher publisher = new RecordingPublisher();
        FinanceSettlementApplicationService service = service(recognitions, publisher);
        InMemorySettlementScheduleRunRepository runs = new InMemorySettlementScheduleRunRepository();
        FinanceSettlementScheduler scheduler = new FinanceSettlementScheduler(service, runs, CLOCK, "node-a", Duration.ofMinutes(10),
            SettlementFrequency.DAILY, new BigDecimal("0.05"), BigDecimal.ZERO);

        scheduler.runDueWork();
        scheduler.runDueWork();

        SettlementScheduleRun reconciliation = runs.findByKey("daily-reconciliation:2026-09-24").orElseThrow();
        SettlementScheduleRun suppliers = runs.findByKey("supplier-settlement:DAILY:2026-09-24:2026-09-24").orElseThrow();
        assertEquals("SUCCEEDED", reconciliation.status());
        assertEquals(1, reconciliation.resultRefs().size());
        assertEquals("SUCCEEDED", suppliers.status());
        assertEquals(1, suppliers.resultRefs().size());
        assertEquals(1, reconciliation.attempts());
        assertEquals(1, suppliers.attempts());
        assertEquals(List.of("sup-1"), service.findSupplierIdsForPeriod(LocalDate.parse("2026-09-24"), LocalDate.parse("2026-09-24")));
        assertEquals(2, publisher.published.size());
    }

    @Test
    void leasePreventsConcurrentOwnerAndFailureCanRetry() {
        InMemorySettlementScheduleRunRepository runs = new InMemorySettlementScheduleRunRepository();
        Instant now = CLOCK.instant();
        assertTrue(runs.acquire("job", "TEST", LocalDate.parse("2026-09-24"), LocalDate.parse("2026-09-24"), "node-a", now, Duration.ofMinutes(1)).isPresent());
        assertFalse(runs.acquire("job", "TEST", LocalDate.parse("2026-09-24"), LocalDate.parse("2026-09-24"), "node-b", now.plusSeconds(30), Duration.ofMinutes(1)).isPresent());
        runs.fail("job", "node-a", "boom", now.plusSeconds(31));
        assertTrue(runs.acquire("job", "TEST", LocalDate.parse("2026-09-24"), LocalDate.parse("2026-09-24"), "node-b", now.plusSeconds(32), Duration.ofMinutes(1)).isPresent());
        runs.complete("job", "node-b", List.of("result-1"), now.plusSeconds(33));
        SettlementScheduleRun completed = runs.findByKey("job").orElseThrow();
        assertEquals("SUCCEEDED", completed.status());
        assertEquals(2, completed.attempts());
        assertEquals(List.of("result-1"), completed.resultRefs());
    }

    private static RevenueRecognition recognition(String order, String supplier, String component, String amount) {
        return RevenueRecognition.recognize(order, supplier, component, Money.of("CNY", amount), "policy-v1", "evt-" + order,
            Instant.parse("2026-09-24T10:00:00Z"), CLOCK.instant(), "cmd-test", "corr-test");
    }

    private static FinanceSettlementApplicationService service(InMemoryRevenueRecognitionRepository recognitions, RecordingPublisher publisher) {
        return new FinanceSettlementApplicationService(recognitions, new InMemoryReconciliationCaseRepository(), new InMemoryInvoiceRepository(),
            new InMemoryFinanceSettlementProjectionRepository(), new InMemoryReconciliationBatchRepository(), new InMemorySupplierSettlementRepository(),
            new InMemoryFeeAccrualRepository(), publisher, new DomainEventEnvelopeMapper(), CLOCK);
    }

    private static final class RecordingPublisher implements EventPublisher {
        private final List<com.trainticket.platformkit.messaging.EventEnvelope> published = new ArrayList<>();
        @Override public void publish(com.trainticket.platformkit.messaging.EventEnvelope envelope) { published.add(envelope); }
    }
}
