package com.trainticket.financesettlement.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record RevenueAllocation(
    String orderId,
    Money grossRevenue,
    Money platformCommission,
    Money taxesWithheld,
    Money supplierPayable,
    Money adjustments
) {
    public RevenueAllocation {
        orderId = orderId == null ? "" : orderId;
        Objects.requireNonNull(grossRevenue, "grossRevenue is required");
        Objects.requireNonNull(platformCommission, "platformCommission is required");
        Objects.requireNonNull(taxesWithheld, "taxesWithheld is required");
        Objects.requireNonNull(supplierPayable, "supplierPayable is required");
        Objects.requireNonNull(adjustments, "adjustments is required");
        grossRevenue.plus(platformCommission).plus(taxesWithheld).plus(supplierPayable).plus(adjustments);
    }

    public static RevenueAllocation calculate(String orderId, Money grossRevenue, BigDecimal commissionPct, BigDecimal withholdingPct, Money adjustments) {
        Objects.requireNonNull(commissionPct, "commissionPct is required");
        Objects.requireNonNull(withholdingPct, "withholdingPct is required");
        Money commission = multiply(grossRevenue, commissionPct);
        Money withheld = multiply(grossRevenue, withholdingPct);
        Money payable = grossRevenue.minus(commission).minus(withheld).plus(adjustments);
        return new RevenueAllocation(orderId, grossRevenue, commission, withheld, payable, adjustments);
    }

    static Money multiply(Money money, BigDecimal pct) {
        BigDecimal amount = money.amount().multiply(pct).setScale(money.currency().getDefaultFractionDigits(), RoundingMode.HALF_UP);
        return new Money(money.currency(), amount);
    }
}
