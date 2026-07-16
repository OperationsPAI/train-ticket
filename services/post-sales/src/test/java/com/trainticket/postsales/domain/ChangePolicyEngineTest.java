package com.trainticket.postsales.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ChangePolicyEngineTest {
    private static final Instant REQUEST = Instant.parse("2026-07-01T00:00:00Z");
    private final ChangePolicyEngine engine = new ChangePolicyEngine();

    @Test
    void sameFareChangePaysFlatDefaultChangeFee() {
        ChangeAssessment assessment = engine.evaluateChange(Money.of("100.00", "CNY"), Money.of("100.00", "CNY"), REQUEST, REQUEST.plusSeconds(3 * 86_400), 0);

        assertEquals(Money.of("15.00", "CNY"), assessment.changeFee());
        assertEquals(Money.of("0.00", "CNY"), assessment.fareDifference());
        assertEquals(Money.of("15.00", "CNY"), assessment.netPayable());
        assertEquals(Money.of("0.00", "CNY"), assessment.netRefundable());
    }

    @Test
    void fareIncreaseAddsToFlatDefaultChangeFee() {
        ChangeAssessment assessment = engine.evaluateChange(Money.of("200.00", "CNY"), Money.of("260.00", "CNY"), REQUEST, REQUEST.plusSeconds(24 * 3_600), 0);

        assertEquals(Money.of("15.00", "CNY"), assessment.changeFee());
        assertEquals(Money.of("60.00", "CNY"), assessment.fareDifference());
        assertEquals(Money.of("75.00", "CNY"), assessment.netPayable());
    }

    @Test
    void fareDecreaseIsRefundedNetOfFlatDefaultChangeFee() {
        ChangeAssessment assessment = engine.evaluateChange(Money.of("200.00", "CNY"), Money.of("140.00", "CNY"), REQUEST, REQUEST.plusSeconds(24 * 3_600), 0);

        assertEquals(Money.of("15.00", "CNY"), assessment.changeFee());
        assertEquals(Money.of("60.00", "CNY"), assessment.fareDifference());
        assertEquals(Money.of("45.00", "CNY"), assessment.netRefundable());
        assertEquals(Money.of("0.00", "CNY"), assessment.netPayable());
    }

    @Test
    void quotedSameFareChangeUsesAmountDueWithoutReapplyingChangeFee() {
        ChangeAssessment assessment = engine.evaluateQuotedChange(
            Money.of("100.00", "CNY"),
            Money.of("15.00", "CNY"),
            Money.zero("CNY"),
            REQUEST,
            REQUEST.plusSeconds(24 * 3_600),
            0
        );

        assertEquals(Money.of("15.00", "CNY"), assessment.changeFee());
        assertEquals(Money.of("0.00", "CNY"), assessment.fareDifference());
        assertEquals(Money.of("15.00", "CNY"), assessment.netPayable());
    }
}
