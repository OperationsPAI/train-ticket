package com.trainticket.financesettlement.domain;

import com.trainticket.platformkit.idempotency.UuidV7;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class FeeAccrual {
    private final String feeAccrualId;
    private final String orderId;
    private final Money platformServiceFee;
    private final Money supplierServiceFee;
    private final Money retainedCancellationFee;
    private final TaxCalculation taxCalculation;
    private final List<FinanceSettlementEvent> domainEvents;
    private long version;

    private FeeAccrual(String feeAccrualId, String orderId, Money platformServiceFee, Money supplierServiceFee, Money retainedCancellationFee, TaxCalculation taxCalculation) {
        this.feeAccrualId = requireText(feeAccrualId, "feeAccrualId");
        this.orderId = requireText(orderId, "orderId");
        this.platformServiceFee = Objects.requireNonNull(platformServiceFee, "platformServiceFee is required");
        this.supplierServiceFee = Objects.requireNonNull(supplierServiceFee, "supplierServiceFee is required");
        this.retainedCancellationFee = Objects.requireNonNull(retainedCancellationFee, "retainedCancellationFee is required");
        this.taxCalculation = Objects.requireNonNull(taxCalculation, "taxCalculation is required");
        this.domainEvents = new ArrayList<>();
    }

    public static FeeAccrual accrue(
        String orderId,
        Money platformServiceFee,
        Money supplierServiceFee,
        Money retainedCancellationFee,
        TaxCalculation taxCalculation,
        Instant now,
        String sourceCommandId,
        String causationId,
        String correlationId
    ) {
        FeeAccrual accrual = new FeeAccrual("fee-" + UuidV7.generate(), orderId, platformServiceFee, supplierServiceFee, retainedCancellationFee, taxCalculation);
        accrual.domainEvents.add(new FeeAccrued(
            accrual.feeAccrualId,
            orderId,
            platformServiceFee,
            supplierServiceFee,
            retainedCancellationFee,
            taxCalculation,
            EventMetadata.create(now, sourceCommandId, causationId, correlationId, Map.of("feeAccrualId", accrual.feeAccrualId, "orderId", orderId))
        ));
        return accrual;
    }

    public static FeeAccrual rehydrate(String feeAccrualId, String orderId, Money platformServiceFee, Money supplierServiceFee, Money retainedCancellationFee, TaxCalculation taxCalculation) {
        return new FeeAccrual(feeAccrualId, orderId, platformServiceFee, supplierServiceFee, retainedCancellationFee, taxCalculation);
    }

    public FeeAccrual withVersion(long version) {
        if (version < 0) throw new DomainRuleViolation("version must not be negative");
        this.version = version;
        return this;
    }

    public String feeAccrualId() { return feeAccrualId; }
    public String orderId() { return orderId; }
    public Money platformServiceFee() { return platformServiceFee; }
    public Money supplierServiceFee() { return supplierServiceFee; }
    public Money retainedCancellationFee() { return retainedCancellationFee; }
    public TaxCalculation taxCalculation() { return taxCalculation; }
    public List<FinanceSettlementEvent> domainEvents() { return Collections.unmodifiableList(domainEvents); }
    public long version() { return version; }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) throw new DomainRuleViolation(name + " must not be blank");
        return value;
    }
}
