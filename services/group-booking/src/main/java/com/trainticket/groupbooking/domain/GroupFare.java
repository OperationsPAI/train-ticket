package com.trainticket.groupbooking.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record GroupFare(String currency, long minorUnits, int discountBasisPoints, String negotiationRef) {
    public GroupFare {
        currency = requireCurrency(currency);
        if (minorUnits < 0) {
            throw new DomainRuleViolation("fare minorUnits must not be negative");
        }
        if (discountBasisPoints < 0 || discountBasisPoints > 10_000) {
            throw new DomainRuleViolation("discountBasisPoints must be between 0 and 10000");
        }
        negotiationRef = blankToNull(negotiationRef);
    }

    public long discountedMinorUnits() {
        BigDecimal multiplier = BigDecimal.valueOf(10_000L - discountBasisPoints).divide(BigDecimal.valueOf(10_000L), 4, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(minorUnits).multiply(multiplier).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static String requireCurrency(String value) {
        if (value == null || !value.matches("[A-Z]{3}")) {
            throw new DomainRuleViolation("currency must be an ISO-4217 uppercase code");
        }
        return value;
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
