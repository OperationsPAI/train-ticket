package com.trainticket.postsales.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RefundPolicyEngineTest {
    private static final Instant REQUEST = Instant.parse("2026-07-01T00:00:00Z");
    private final RefundPolicyEngine engine = new RefundPolicyEngine();

    @Test
    void voluntaryRefundTwentyDaysBeforeDepartureAppliesFivePercentPenalty() {
        RefundAssessment assessment = engine.evaluateRefund(Money.of("200.00", "CNY"), REQUEST, REQUEST.plusSeconds(20 * 86_400), false, "ADULT", 1);

        assertEquals(new BigDecimal("0.05"), assessment.penaltyPct());
        assertEquals(Money.of("10.00", "CNY"), assessment.penaltyAmount());
        assertEquals(Money.of("190.00", "CNY"), assessment.refundableAmount());
        assertEquals("TIER_GT_15_DAYS", assessment.tierApplied());
    }

    @Test
    void voluntaryRefundThreeDaysBeforeDepartureAppliesTwentyPercentPenalty() {
        RefundAssessment assessment = engine.evaluateRefund(Money.of("200.00", "CNY"), REQUEST, REQUEST.plusSeconds(3 * 86_400), false, "ADULT", 1);

        assertEquals(new BigDecimal("0.2"), assessment.penaltyPct());
        assertEquals(Money.of("40.00", "CNY"), assessment.penaltyAmount());
        assertEquals(Money.of("160.00", "CNY"), assessment.refundableAmount());
    }

    @Test
    void involuntaryCarrierRefundBypassesPenalty() {
        RefundAssessment assessment = engine.evaluateRefund(
            RefundWaterfall.simple(Money.of("200.00", "CNY")),
            Money.of("200.00", "CNY"),
            REQUEST,
            REQUEST.plusSeconds(3_600),
            RefundClassification.INVOLUNTARY_CARRIER,
            "ADULT",
            1
        );

        assertEquals(BigDecimal.ZERO, assessment.penaltyPct());
        assertEquals(Money.of("0.00", "CNY"), assessment.penaltyAmount());
        assertEquals(Money.of("200.00", "CNY"), assessment.refundableAmount());
    }

    @Test
    void specialRulesApplyForStudentAndGroupBookings() {
        RefundAssessment student = engine.evaluateRefund(Money.of("200.00", "CNY"), REQUEST, REQUEST.plusSeconds(3 * 86_400), false, "STUDENT", 1);
        RefundAssessment group = engine.evaluateRefund(Money.of("200.00", "CNY"), REQUEST, REQUEST.plusSeconds(3 * 86_400), false, "ADULT", 10);

        assertEquals(Money.of("0.00", "CNY"), student.penaltyAmount());
        assertEquals("STUDENT_GT_2D_FREE", student.tierApplied());
        assertEquals(Money.of("10.00", "CNY"), group.penaltyAmount());
        assertEquals("GROUP_FLAT_5_PERCENT", group.tierApplied());
    }

    @Test
    void waterfallRetainsPlatformServiceFeeRefundsTaxAncillaryAndBaseFareMinusPenalty() {
        RefundWaterfall waterfall = new RefundWaterfall(
            Money.of("200.00", "CNY"),
            Money.of("20.00", "CNY"),
            Money.of("10.00", "CNY"),
            Money.of("0.00", "CNY"),
            Money.of("50.00", "CNY"),
            Money.of("0.00", "CNY"),
            false
        );

        RefundAssessment assessment = engine.evaluateRefund(
            waterfall,
            Money.of("200.00", "CNY"),
            REQUEST,
            REQUEST.plusSeconds(3 * 86_400),
            RefundClassification.VOLUNTARY,
            "ADULT",
            1
        );

        assertEquals(Money.of("230.00", "CNY"), assessment.refundableAmount());
        assertTrue(assessment.componentDecisions().stream().anyMatch(c -> c.componentType().equals("PLATFORM_SERVICE_FEE") && c.retainedAmount().equals(Money.of("10.00", "CNY"))));
        assertTrue(assessment.componentDecisions().stream().anyMatch(c -> c.componentType().equals("TAXES") && c.refundableAmount().equals(Money.of("20.00", "CNY"))));
    }
}
