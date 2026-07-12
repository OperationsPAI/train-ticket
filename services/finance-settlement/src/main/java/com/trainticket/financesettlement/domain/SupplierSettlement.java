package com.trainticket.financesettlement.domain;

import com.trainticket.platformkit.idempotency.UuidV7;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SupplierSettlement {
    private final String supplierSettlementId;
    private final String supplierId;
    private final SettlementPeriod period;
    private final BigDecimal platformCommissionPct;
    private final BigDecimal withholdingTaxPct;
    private final List<RevenueAllocation> allocations;
    private final List<FinanceSettlementEvent> domainEvents;
    private final Money grossRevenue;
    private final Money platformCommission;
    private final Money taxesWithheld;
    private final Money supplierPayable;
    private final Money adjustments;
    private long version;

    private SupplierSettlement(
        String supplierSettlementId,
        String supplierId,
        SettlementPeriod period,
        BigDecimal platformCommissionPct,
        BigDecimal withholdingTaxPct,
        List<RevenueAllocation> allocations,
        Currency currency
    ) {
        this.supplierSettlementId = requireText(supplierSettlementId, "supplierSettlementId");
        this.supplierId = requireText(supplierId, "supplierId");
        this.period = Objects.requireNonNull(period, "period is required");
        this.platformCommissionPct = validPct(platformCommissionPct, "platformCommissionPct");
        this.withholdingTaxPct = validPct(withholdingTaxPct, "withholdingTaxPct");
        this.allocations = new ArrayList<>(List.copyOf(allocations));
        this.domainEvents = new ArrayList<>();
        this.grossRevenue = sum(currency, this.allocations.stream().map(RevenueAllocation::grossRevenue).toList());
        this.platformCommission = sum(currency, this.allocations.stream().map(RevenueAllocation::platformCommission).toList());
        this.taxesWithheld = sum(currency, this.allocations.stream().map(RevenueAllocation::taxesWithheld).toList());
        this.supplierPayable = sum(currency, this.allocations.stream().map(RevenueAllocation::supplierPayable).toList());
        this.adjustments = sum(currency, this.allocations.stream().map(RevenueAllocation::adjustments).toList());
    }

    public static SupplierSettlement calculate(
        String supplierId,
        SettlementPeriod period,
        BigDecimal platformCommissionPct,
        BigDecimal withholdingTaxPct,
        List<RevenueAllocation> allocations,
        Currency currency,
        Instant now,
        String sourceCommandId,
        String causationId,
        String correlationId
    ) {
        SupplierSettlement settlement = new SupplierSettlement(
            "ss-" + UuidV7.generate(), supplierId, period, platformCommissionPct, withholdingTaxPct, allocations, currency);
        settlement.domainEvents.add(new SupplierSettlementCalculated(
            settlement.supplierSettlementId,
            supplierId,
            period,
            settlement.grossRevenue,
            settlement.platformCommission,
            settlement.taxesWithheld,
            settlement.supplierPayable,
            settlement.adjustments,
            EventMetadata.create(now, sourceCommandId, causationId, correlationId, Map.of("supplierSettlementId", settlement.supplierSettlementId, "supplierId", supplierId))
        ));
        return settlement;
    }

    public static SupplierSettlement rehydrate(
        String supplierSettlementId,
        String supplierId,
        SettlementPeriod period,
        BigDecimal platformCommissionPct,
        BigDecimal withholdingTaxPct,
        List<RevenueAllocation> allocations,
        Currency currency
    ) {
        return new SupplierSettlement(supplierSettlementId, supplierId, period, platformCommissionPct, withholdingTaxPct, allocations, currency);
    }

    public SupplierSettlement withVersion(long version) {
        if (version < 0) throw new DomainRuleViolation("version must not be negative");
        this.version = version;
        return this;
    }

    private static Money sum(Currency currency, List<Money> amounts) {
        return amounts.stream().reduce(Money.zero(currency), Money::plus);
    }

    private static BigDecimal validPct(BigDecimal value, String name) {
        Objects.requireNonNull(value, name + " is required");
        if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new DomainRuleViolation(name + " must be between 0 and 1");
        }
        return value;
    }

    public String supplierSettlementId() { return supplierSettlementId; }
    public String supplierId() { return supplierId; }
    public SettlementPeriod period() { return period; }
    public BigDecimal platformCommissionPct() { return platformCommissionPct; }
    public BigDecimal withholdingTaxPct() { return withholdingTaxPct; }
    public List<RevenueAllocation> allocations() { return Collections.unmodifiableList(allocations); }
    public Money grossRevenue() { return grossRevenue; }
    public Money platformCommission() { return platformCommission; }
    public Money taxesWithheld() { return taxesWithheld; }
    public Money supplierPayable() { return supplierPayable; }
    public Money adjustments() { return adjustments; }
    public List<FinanceSettlementEvent> domainEvents() { return Collections.unmodifiableList(domainEvents); }
    public long version() { return version; }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) throw new DomainRuleViolation(name + " must not be blank");
        return value;
    }
}
