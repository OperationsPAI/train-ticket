package com.trainticket.postsales.domain;

import java.math.BigDecimal;
import java.util.Objects;

public record RefundTier(
    Integer minDaysBefore,
    Integer maxDaysBefore,
    BigDecimal penaltyPct,
    Money minimumPenalty,
    String name
) {
    public RefundTier {
        if (minDaysBefore != null && minDaysBefore < 0) {
            throw new DomainRuleViolation("minimum days before departure must not be negative");
        }
        if (maxDaysBefore != null && maxDaysBefore < 0) {
            throw new DomainRuleViolation("maximum days before departure must not be negative");
        }
        if (minDaysBefore != null && maxDaysBefore != null && minDaysBefore > maxDaysBefore) {
            throw new DomainRuleViolation("minimum days before departure must be less than maximum days before departure");
        }
        penaltyPct = Objects.requireNonNull(penaltyPct, "penaltyPct is required").stripTrailingZeros();
        if (penaltyPct.signum() < 0 || penaltyPct.compareTo(BigDecimal.ONE) > 0) {
            throw new DomainRuleViolation("penaltyPct must be between 0 and 1");
        }
        minimumPenalty = Objects.requireNonNull(minimumPenalty, "minimumPenalty is required");
        name = PostSalesScope.requireText(name, "name");
    }
}
