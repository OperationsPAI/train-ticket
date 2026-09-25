package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.SettlementFrequency;
import com.trainticket.financesettlement.domain.SettlementPeriod;
import com.trainticket.platformkit.idempotency.UuidV7;
import com.trainticket.platformkit.messaging.PrefixedIds;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(javax.sql.DataSource.class)
@ConditionalOnProperty(name = "finance.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class FinanceSettlementScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(FinanceSettlementScheduler.class);
    private final FinanceSettlementApplicationService service;
    private final SettlementScheduleRunRepository runs;
    private final Clock clock;
    private final String owner;
    private final Duration leaseDuration;
    private final SettlementFrequency supplierFrequency;
    private final BigDecimal commissionPct;
    private final BigDecimal withholdingPct;

    @Autowired
    public FinanceSettlementScheduler(FinanceSettlementApplicationService service, SettlementScheduleRunRepository runs, Clock clock,
                                      @Value("${finance.scheduler.instance-id:}") String configuredOwner,
                                      @Value("${finance.scheduler.lease-seconds:600}") long leaseSeconds,
                                      @Value("${finance.supplier-settlement.frequency:DAILY}") String supplierFrequency,
                                      @Value("${finance.supplier-settlement.commission-pct:0.05}") BigDecimal commissionPct,
                                      @Value("${finance.supplier-settlement.withholding-pct:0.00}") BigDecimal withholdingPct) {
        this(service, runs, clock, configuredOwner == null || configuredOwner.isBlank() ? "finance-" + UuidV7.generate() : configuredOwner,
            Duration.ofSeconds(Math.max(30, leaseSeconds)), SettlementFrequency.valueOf(supplierFrequency.trim().toUpperCase()), commissionPct, withholdingPct);
    }

    FinanceSettlementScheduler(FinanceSettlementApplicationService service, SettlementScheduleRunRepository runs, Clock clock,
                               String owner, Duration leaseDuration, SettlementFrequency supplierFrequency,
                               BigDecimal commissionPct, BigDecimal withholdingPct) {
        this.service = service;
        this.runs = runs;
        this.clock = clock;
        this.owner = owner;
        this.leaseDuration = leaseDuration;
        this.supplierFrequency = supplierFrequency;
        this.commissionPct = commissionPct;
        this.withholdingPct = withholdingPct;
    }

    @Scheduled(initialDelayString = "${finance.scheduler.initial-delay-ms:15000}", fixedDelayString = "${finance.scheduler.interval-ms:60000}")
    public void runDueWork() {
        LocalDate settlementDate = LocalDate.now(clock).minusDays(1);
        runDailyReconciliation(settlementDate);
        SettlementPeriod period = closedPeriod(LocalDate.now(clock), supplierFrequency);
        runSupplierSettlements(period);
    }

    void runDailyReconciliation(LocalDate settlementDate) {
        String key = "daily-reconciliation:" + settlementDate;
        var acquired = runs.acquire(key, "DAILY_RECONCILIATION", settlementDate, settlementDate, owner, clock.instant(), leaseDuration);
        if (acquired.isEmpty()) return;
        try {
            var batch = service.runDailyReconciliation(settlementDate, PrefixedIds.newCorrelationId());
            runs.complete(key, owner, List.of(batch.batchId()), clock.instant());
        } catch (RuntimeException error) {
            runs.fail(key, owner, summarize(error), clock.instant());
            LOGGER.error("finance settlement scheduled reconciliation failed jobKey={}", key, error);
        }
    }

    void runSupplierSettlements(SettlementPeriod period) {
        String key = "supplier-settlement:" + period.frequency() + ":" + period.startDate() + ":" + period.endDate();
        var acquired = runs.acquire(key, "SUPPLIER_SETTLEMENT", period.startDate(), period.endDate(), owner, clock.instant(), leaseDuration);
        if (acquired.isEmpty()) return;
        try {
            List<String> resultRefs = new ArrayList<>();
            for (String supplierId : service.findSupplierIdsForPeriod(period.startDate(), period.endDate())) {
                var settlement = service.calculateSupplierSettlement(supplierId, period.startDate(), period.endDate(), period.frequency(),
                    commissionPct, withholdingPct, PrefixedIds.newCorrelationId());
                resultRefs.add(settlement.supplierSettlementId());
            }
            runs.complete(key, owner, resultRefs, clock.instant());
        } catch (RuntimeException error) {
            runs.fail(key, owner, summarize(error), clock.instant());
            LOGGER.error("finance supplier settlement schedule failed jobKey={}", key, error);
        }
    }

    static SettlementPeriod closedPeriod(LocalDate today, SettlementFrequency frequency) {
        return switch (frequency) {
            case DAILY -> new SettlementPeriod(today.minusDays(1), today.minusDays(1), frequency);
            case WEEKLY -> {
                LocalDate currentWeekStart = today.minusDays(today.getDayOfWeek().getValue() - 1L);
                yield new SettlementPeriod(currentWeekStart.minusWeeks(1), currentWeekStart.minusDays(1), frequency);
            }
            case MONTHLY -> {
                LocalDate currentMonthStart = today.withDayOfMonth(1);
                LocalDate end = currentMonthStart.minusDays(1);
                yield new SettlementPeriod(end.withDayOfMonth(1), end, frequency);
            }
        };
    }

    static String summarize(RuntimeException error) {
        String value = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }
}
