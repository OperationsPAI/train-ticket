package com.trainticket.postsales.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

public final class RefundWaterfall {
    private final Money baseFare;
    private final Money taxes;
    private final Money platformServiceFee;
    private final Money supplierServiceFee;
    private final Money ancillary;
    private final Money discounts;
    private final boolean ancillaryUsed;

    public RefundWaterfall(
        Money baseFare,
        Money taxes,
        Money platformServiceFee,
        Money supplierServiceFee,
        Money ancillary,
        Money discounts,
        boolean ancillaryUsed
    ) {
        this.baseFare = Objects.requireNonNull(baseFare, "baseFare is required");
        this.taxes = requireSameCurrency(taxes, baseFare, "taxes");
        this.platformServiceFee = requireSameCurrency(platformServiceFee, baseFare, "platformServiceFee");
        this.supplierServiceFee = requireSameCurrency(supplierServiceFee, baseFare, "supplierServiceFee");
        this.ancillary = requireSameCurrency(ancillary, baseFare, "ancillary");
        this.discounts = requireSameCurrency(discounts, baseFare, "discounts");
        this.ancillaryUsed = ancillaryUsed;
    }

    public static RefundWaterfall simple(Money baseFare) {
        Money zero = Money.zero(baseFare.currency());
        return new RefundWaterfall(baseFare, zero, zero, zero, zero, zero, false);
    }

    public RefundWaterfallResult apply(Money requestedRefundable, Money penaltyAmount) {
        Objects.requireNonNull(requestedRefundable, "requestedRefundable is required").compareTo(baseFare);
        Objects.requireNonNull(penaltyAmount, "penaltyAmount is required").compareTo(baseFare);
        Currency currency = baseFare.currency();
        Money zero = Money.zero(currency);
        List<RefundComponentDecision> decisions = new ArrayList<>();

        Money ancillaryRefund = ancillaryUsed ? zero : ancillary;
        decisions.add(new RefundComponentDecision(
            "ANCILLARY",
            ancillary,
            ancillaryRefund,
            ancillaryUsed ? ancillary : zero,
            !ancillaryUsed,
            ancillaryUsed ? "ancillary already used" : "unused ancillary refundable"
        ));

        decisions.add(new RefundComponentDecision(
            "PLATFORM_SERVICE_FEE",
            platformServiceFee,
            zero,
            platformServiceFee,
            false,
            "platform service fee retained"
        ));

        decisions.add(new RefundComponentDecision(
            "SUPPLIER_SERVICE_FEE",
            supplierServiceFee,
            supplierServiceFee,
            zero,
            true,
            "supplier service fee returned"
        ));

        decisions.add(new RefundComponentDecision(
            "TAXES",
            taxes,
            taxes,
            zero,
            true,
            "taxes fully refundable"
        ));

        Money basePenalty = min(penaltyAmount, baseFare);
        Money baseRefund = subtract(baseFare, basePenalty);
        decisions.add(new RefundComponentDecision(
            "BASE_FARE",
            baseFare,
            baseRefund,
            basePenalty,
            !baseRefund.isZero(),
            basePenalty.isZero() ? "base fare refundable" : "base fare penalty retained"
        ));

        decisions.add(new RefundComponentDecision(
            "DISCOUNTS",
            discounts,
            zero,
            discounts,
            false,
            discounts.isZero() ? "no discount claw back" : "discount clawed back"
        ));

        Money calculated = ancillaryRefund.add(supplierServiceFee).add(taxes).add(baseRefund);
        Money afterDiscountClawBack = subtract(calculated, discounts);
        return new RefundWaterfallResult(afterDiscountClawBack, decisions);
    }

    private static Money requireSameCurrency(Money money, Money base, String name) {
        Objects.requireNonNull(money, name + " is required").compareTo(base);
        return money;
    }

    static Money percent(Money money, BigDecimal pct) {
        BigDecimal amount = money.amount().multiply(pct).setScale(money.currency().getDefaultFractionDigits(), RoundingMode.HALF_UP);
        return new Money(amount, money.currency());
    }

    static Money subtract(Money left, Money right) {
        left.compareTo(right);
        BigDecimal result = left.amount().subtract(right.amount());
        if (result.signum() < 0) {
            result = BigDecimal.ZERO;
        }
        return new Money(result, left.currency());
    }

    static Money min(Money left, Money right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    public record RefundWaterfallResult(Money refundableAmount, List<RefundComponentDecision> componentDecisions) {
        public RefundWaterfallResult {
            Objects.requireNonNull(refundableAmount, "refundableAmount is required");
            componentDecisions = List.copyOf(Objects.requireNonNull(componentDecisions, "componentDecisions are required"));
        }
    }
}
