package com.trainticket.postsales.application;

import com.trainticket.postsales.domain.Money;
import com.trainticket.postsales.domain.RefundWaterfall;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record PostSalesPolicyContext(
    String journeyOrderId,
    Instant departureTime,
    Map<String, String> travelerTypesByRef,
    int groupSize,
    int appliedChangeCount,
    Money originalFare,
    RefundWaterfallComponents refundComponents
) {
    public PostSalesPolicyContext {
        journeyOrderId = requireText(journeyOrderId, "journeyOrderId");
        Objects.requireNonNull(departureTime, "departureTime is required");
        travelerTypesByRef = Map.copyOf(Objects.requireNonNull(travelerTypesByRef, "travelerTypesByRef is required"));
        if (groupSize < 1) {
            throw new IllegalArgumentException("groupSize must be positive");
        }
        if (appliedChangeCount < 0) {
            throw new IllegalArgumentException("appliedChangeCount must not be negative");
        }
        originalFare = Objects.requireNonNull(originalFare, "originalFare is required");
        refundComponents = Objects.requireNonNull(refundComponents, "refundComponents is required");
    }

    public static PostSalesPolicyContext fallback(String journeyOrderId, Instant requestTime, int scopedTravelerCount, Money amount) {
        int groupSize = Math.max(1, scopedTravelerCount);
        return new PostSalesPolicyContext(
            journeyOrderId,
            requestTime,
            Map.of(),
            groupSize,
            0,
            amount,
            RefundWaterfallComponents.empty(amount.currency())
        );
    }

    public String travelerType(List<String> scopedTravelerRefs) {
        for (String travelerRef : scopedTravelerRefs) {
            String travelerType = travelerTypesByRef.get(travelerRef);
            if (travelerType != null && !travelerType.isBlank()) {
                return travelerType;
            }
        }
        return travelerTypesByRef.values().stream()
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .orElse("UNKNOWN");
    }

    public Money originalFareOr(Money fallback) {
        return originalFare.isZero() ? fallback : originalFare;
    }

    public RefundWaterfall waterfallFor(Money penaltyBase) {
        return refundComponents.toWaterfall(penaltyBase);
    }

    public PostSalesPolicyContext incrementAppliedChangeCount() {
        return new PostSalesPolicyContext(
            journeyOrderId,
            departureTime,
            travelerTypesByRef,
            groupSize,
            appliedChangeCount + 1,
            originalFare,
            refundComponents
        );
    }

    public PostSalesPolicyContext withAncillaryComponent(Money ancillaryAmount, boolean ancillaryUsed) {
        if (ancillaryAmount == null) {
            return this;
        }
        return new PostSalesPolicyContext(
            journeyOrderId,
            departureTime,
            travelerTypesByRef,
            groupSize,
            appliedChangeCount,
            originalFare,
            refundComponents.withAncillary(ancillaryAmount, ancillaryUsed)
        );
    }

    public record RefundWaterfallComponents(
        Money baseFare,
        Money taxes,
        Money platformServiceFee,
        Money supplierServiceFee,
        Money ancillary,
        Money discounts,
        boolean ancillaryUsed
    ) {
        public RefundWaterfallComponents {
            baseFare = Objects.requireNonNull(baseFare, "baseFare is required");
            taxes = sameCurrency(taxes, baseFare, "taxes");
            platformServiceFee = sameCurrency(platformServiceFee, baseFare, "platformServiceFee");
            supplierServiceFee = sameCurrency(supplierServiceFee, baseFare, "supplierServiceFee");
            ancillary = sameCurrency(ancillary, baseFare, "ancillary");
            discounts = sameCurrency(discounts, baseFare, "discounts");
        }

        public static RefundWaterfallComponents empty(Currency currency) {
            Money zero = Money.zero(currency);
            return new RefundWaterfallComponents(zero, zero, zero, zero, zero, zero, false);
        }

        public RefundWaterfallComponents withAncillary(Money ancillaryAmount, boolean used) {
            Money compatibleAmount = convertIfZeroCurrency(ancillaryAmount, baseFare);
            compatibleAmount.compareTo(baseFare);
            return new RefundWaterfallComponents(
                baseFare,
                taxes,
                platformServiceFee,
                supplierServiceFee,
                compatibleAmount,
                discounts,
                used || ancillaryUsed
            );
        }

        public RefundWaterfall toWaterfall(Money fallbackBaseFare) {
            if (baseFare.isZero() && taxes.isZero() && platformServiceFee.isZero()
                && supplierServiceFee.isZero() && ancillary.isZero() && discounts.isZero()) {
                return RefundWaterfall.simple(fallbackBaseFare);
            }
            Money effectiveBaseFare = baseFare.isZero() ? fallbackBaseFare : baseFare;
            return new RefundWaterfall(
                effectiveBaseFare,
                convertIfZeroCurrency(taxes, effectiveBaseFare),
                convertIfZeroCurrency(platformServiceFee, effectiveBaseFare),
                convertIfZeroCurrency(supplierServiceFee, effectiveBaseFare),
                convertIfZeroCurrency(ancillary, effectiveBaseFare),
                convertIfZeroCurrency(discounts, effectiveBaseFare),
                ancillaryUsed
            );
        }

        private static Money convertIfZeroCurrency(Money value, Money base) {
            return value.isZero() && !value.currency().equals(base.currency()) ? Money.zero(base.currency()) : value;
        }

        private static Money sameCurrency(Money value, Money base, String name) {
            Objects.requireNonNull(value, name + " is required").compareTo(base);
            return value;
        }
    }

    static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public static Optional<Instant> earliest(List<Instant> instants) {
        return instants.stream().min(Instant::compareTo);
    }
}
