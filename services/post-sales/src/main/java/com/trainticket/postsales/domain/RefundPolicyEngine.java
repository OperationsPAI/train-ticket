package com.trainticket.postsales.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class RefundPolicyEngine {
    private static final BigDecimal ZERO_PCT = new BigDecimal("0.00");
    private static final BigDecimal FIVE_PCT = new BigDecimal("0.05");
    private static final BigDecimal TEN_PCT = new BigDecimal("0.10");
    private static final BigDecimal TWENTY_PCT = new BigDecimal("0.20");
    private static final BigDecimal FULL_PCT = new BigDecimal("1.00");

    public RefundAssessment evaluateRefund(
        Money originalFare,
        Instant requestTime,
        Instant departureTime,
        boolean involuntary,
        String travelerType,
        int groupSize
    ) {
        RefundClassification classification = involuntary ? RefundClassification.INVOLUNTARY_CARRIER : RefundClassification.VOLUNTARY;
        return evaluateRefund(RefundWaterfall.simple(originalFare), originalFare, requestTime, departureTime, classification, travelerType, groupSize);
    }

    public RefundAssessment evaluateRefund(
        RefundWaterfall waterfall,
        Money penaltyBase,
        Instant requestTime,
        Instant departureTime,
        RefundClassification classification,
        String travelerType,
        int groupSize
    ) {
        Objects.requireNonNull(waterfall, "waterfall is required");
        Objects.requireNonNull(penaltyBase, "penaltyBase is required");
        Objects.requireNonNull(requestTime, "requestTime is required");
        Objects.requireNonNull(departureTime, "departureTime is required");
        classification = Objects.requireNonNull(classification, "classification is required");
        if (groupSize < 1) {
            throw new DomainRuleViolation("groupSize must be positive");
        }

        TierChoice choice = chooseTier(requestTime, departureTime, classification, travelerType, groupSize, penaltyBase);
        Money penalty = RefundWaterfall.percent(penaltyBase, choice.penaltyPct());
        if (!choice.minimumPenalty().isZero() && !penalty.isZero() && penalty.compareTo(choice.minimumPenalty()) < 0) {
            penalty = choice.minimumPenalty();
        }
        penalty = RefundWaterfall.min(penalty, penaltyBase);
        RefundWaterfall.RefundWaterfallResult waterfallResult = waterfall.apply(penaltyBase, penalty);
        return new RefundAssessment(
            waterfallResult.refundableAmount(),
            penalty,
            choice.penaltyPct(),
            choice.tierApplied(),
            choice.explanation(),
            classification,
            waterfallResult.componentDecisions()
        );
    }

    private TierChoice chooseTier(
        Instant requestTime,
        Instant departureTime,
        RefundClassification classification,
        String travelerType,
        int groupSize,
        Money penaltyBase
    ) {
        if (classification.isInvoluntary()) {
            return new TierChoice(ZERO_PCT, Money.zero(penaltyBase.currency()), "INVOLUNTARY_OVERRIDE", "involuntary refund bypasses penalty tiers");
        }
        Duration beforeDeparture = Duration.between(requestTime, departureTime);
        if (beforeDeparture.isNegative() || beforeDeparture.isZero()) {
            return new TierChoice(FULL_PCT, Money.zero(penaltyBase.currency()), "AFTER_DEPARTURE_NON_REFUNDABLE", "refund requested after departure is non-refundable");
        }
        if (groupSize >= 10) {
            return new TierChoice(FIVE_PCT, Money.zero(penaltyBase.currency()), "GROUP_FLAT_5_PERCENT", "group booking refund applies flat 5% fee");
        }
        if (isStudent(travelerType) && beforeDeparture.compareTo(Duration.ofDays(2)) > 0) {
            return new TierChoice(ZERO_PCT, Money.zero(penaltyBase.currency()), "STUDENT_GT_2D_FREE", "student ticket refunded more than 2 days before departure has no fee");
        }
        if (beforeDeparture.compareTo(Duration.ofHours(48)) < 0) {
            return new TierChoice(FULL_PCT, Money.zero(penaltyBase.currency()), "LT_48H_NON_REFUNDABLE", "refund requested less than 48 hours before departure is non-refundable");
        }
        if (beforeDeparture.compareTo(Duration.ofDays(8)) < 0) {
            return new TierChoice(TWENTY_PCT, Money.zero(penaltyBase.currency()), "TIER_2_TO_7_DAYS", "voluntary refund 2-7 days before departure applies 20% fee");
        }
        if (beforeDeparture.compareTo(Duration.ofDays(15)) <= 0) {
            return new TierChoice(TEN_PCT, Money.zero(penaltyBase.currency()), "TIER_8_TO_15_DAYS", "voluntary refund 8-15 days before departure applies 10% fee");
        }
        return new TierChoice(FIVE_PCT, Money.of("2.00", penaltyBase.currency().getCurrencyCode()), "TIER_GT_15_DAYS", "voluntary refund more than 15 days before departure applies 5% fee");
    }

    private static boolean isStudent(String travelerType) {
        return travelerType != null && travelerType.toUpperCase(Locale.ROOT).contains("STUDENT");
    }

    private record TierChoice(BigDecimal penaltyPct, Money minimumPenalty, String tierApplied, String explanation) { }
}
