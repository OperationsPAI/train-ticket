package com.trainticket.walletpromotion.domain;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PromotionInstrumentTest {
    // The benefit timeline below is pinned deliberately: every transition is
    // handed an explicit `now` on that same timeline, so the wall clock plays no
    // part and these literals are deterministic inputs, not a time bomb.
    // Two calls previously passed Instant.now() against this pinned window.
    // Since the window closed (2026-02-01) those calls were expiring first, so
    // reserve()'s requireNotExpired guard fired instead of the status guard the
    // assertion is named for -- passing for the wrong reason. Now pinned.
    private static final Instant ISSUED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant VALID_UNTIL = Instant.parse("2026-02-01T00:00:00Z");

    @Test void enforcesTransitionsAndBalances() {
        PromotionInstrument b = benefit();
        PromotionInstrument reserved = b.reserve(new Money("USD", 40), reason(), Instant.parse("2026-01-01T00:00:01Z"));
        assertThat(reserved.status()).isEqualTo(PromotionStatus.RESERVED);
        assertThat(reserved.availableAmount().minorUnits()).isEqualTo(60);
        // revoke() has no expiry guard, so this always tested the status rule;
        // pin it anyway so the whole test sits on one timeline.
        assertThatThrownBy(() -> reserved.revoke(reason(), Instant.parse("2026-01-01T00:00:01Z")))
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("reserved amount must be zero");
        PromotionInstrument redeemed = reserved.redeem(new Money("USD", 40), reason(), Instant.parse("2026-01-01T00:00:02Z"), true);
        assertThat(redeemed.status()).isEqualTo(PromotionStatus.ISSUED);
        PromotionInstrument reversed = redeemed.reverse(new Money("USD", 40), reason(), Instant.parse("2026-01-01T00:00:03Z"));
        assertThat(reversed.status()).isEqualTo(PromotionStatus.REVERSED);
        assertThat(reversed.reserve(new Money("USD", 1), reason(), Instant.parse("2026-01-01T00:00:04Z")).status())
            .isEqualTo(PromotionStatus.RESERVED);
    }

    @Test void reserveRejectsExpiredBenefitIndependentlyOfStatus() {
        // Expiry deserves its own explicit test rather than being an accidental
        // side effect of a stale fixture in the transition test above.
        assertThatThrownBy(() -> benefit().reserve(new Money("USD",1), reason(), VALID_UNTIL))
            .isInstanceOf(DomainException.class)
            .hasMessageContaining("benefit validity has expired");
    }

    static PromotionInstrument benefit() { return PromotionInstrument.issue("ben-1","acc-1","wac-1",BenefitType.BALANCE,BalanceType.PROMOTION_CREDIT,new Money("USD",100),IssuanceSource.MANUAL_OPS,null,new ApplicableScope(BenefitScopeType.ANY_TRIP,null,"USD"),new RedemptionRule(false,null,false),new RevocationRule(null),ISSUED_AT,VALID_UNTIL,reason(),ISSUED_AT); }
    static BusinessReason reason(){ return new BusinessReason(ReasonType.MANUAL_OPS,"TEST","MANUAL_ACTION","act-1",null); }
}
