package com.trainticket.financesettlement.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;

class FinanceSettlementEnrichmentTest {
    private static final Instant NOW = Instant.parse("2026-07-03T12:00:00Z");

    @Test
    void oneHundredPaymentsCreateReconciliationBatchWithAllEntries() {
        List<ReconciliationEntry> entries = java.util.stream.IntStream.range(0, 100)
            .mapToObj(index -> ReconciliationEntry.compare(
                "entry-" + index,
                "order-" + index,
                "payment-" + index,
                Money.of("CNY", "10.00"),
                Money.of("CNY", "10.00"),
                true,
                true,
                List.of("evt-" + index)))
            .toList();
        ReconciliationBatch batch = ReconciliationBatch.complete(LocalDate.parse("2026-07-02"), NOW, Currency.getInstance("CNY"), entries, NOW, "cmd-1", "evt-1", "corr-1");
        assertEquals(100, batch.entries().size());
        assertEquals(100, batch.report().matchedEntries());
        assertEquals(new BigDecimal("1.0000"), batch.report().matchRate());
        assertInstanceOf(ReconciliationCompleted.class, batch.domainEvents().getFirst());
    }

    @Test
    void amountMismatchIsFlaggedInReconciliationReport() {
        ReconciliationEntry entry = ReconciliationEntry.compare("entry-1", "order-1", "payment-1",
            Money.of("CNY", "12.00"), Money.of("CNY", "10.00"), true, true, List.of("evt-1"));
        ReconciliationBatch batch = ReconciliationBatch.complete(LocalDate.parse("2026-07-02"), NOW, Currency.getInstance("CNY"), List.of(entry), NOW, "cmd-1", "evt-1", "corr-1");
        assertEquals(ReconciliationStatus.AMOUNT_MISMATCH, entry.status());
        assertEquals(1, batch.report().exceptions().size());
        assertEquals(Money.of("CNY", "2.00"), batch.report().totalVariance());
    }

    @Test
    void supplierWithFivePercentCommissionCalculatesNet() {
        RevenueAllocation allocation = RevenueAllocation.calculate("order-1", Money.of("CNY", "100.00"), new BigDecimal("0.05"), BigDecimal.ZERO, Money.of("CNY", "0.00"));
        SupplierSettlement settlement = SupplierSettlement.calculate("supplier-1", new SettlementPeriod(LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-01"), SettlementFrequency.DAILY),
            new BigDecimal("0.05"), BigDecimal.ZERO, List.of(allocation), Currency.getInstance("CNY"), NOW, "cmd-1", "evt-1", "corr-1");
        assertEquals(Money.of("CNY", "5.00"), settlement.platformCommission());
        assertEquals(Money.of("CNY", "95.00"), settlement.supplierPayable());
        assertInstanceOf(SupplierSettlementCalculated.class, settlement.domainEvents().getFirst());
    }

    @Test
    void refundReversesTaxAndAdjustsSupplierPayable() {
        RevenueAllocation allocation = RevenueAllocation.calculate("order-1", Money.of("CNY", "100.00"), new BigDecimal("0.05"), BigDecimal.ZERO, Money.of("CNY", "-20.00"));
        TaxCalculation tax = TaxCalculation.calculate(Money.of("CNY", "5.00"), Money.of("CNY", "100.00"), allocation.supplierPayable(), BigDecimal.ZERO, Money.of("CNY", "20.00"));
        assertEquals(Money.of("CNY", "75.00"), allocation.supplierPayable());
        assertEquals(Money.of("CNY", "-0.01"), tax.taxReversal());
    }
}
