package com.trainticket.walletpromotion.domain;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PromotionInstrumentTest {
    @Test void enforcesTransitionsAndBalances() {
        PromotionInstrument b = benefit();
        PromotionInstrument reserved = b.reserve(new Money("USD", 40), reason(), Instant.parse("2026-01-01T00:00:01Z"));
        assertThat(reserved.status()).isEqualTo(PromotionStatus.RESERVED);
        assertThat(reserved.availableAmount().minorUnits()).isEqualTo(60);
        assertThatThrownBy(() -> reserved.revoke(reason(), Instant.now())).isInstanceOf(DomainException.class);
        PromotionInstrument redeemed = reserved.redeem(new Money("USD", 40), reason(), Instant.parse("2026-01-01T00:00:02Z"), true);
        assertThat(redeemed.status()).isEqualTo(PromotionStatus.REDEEMED);
        PromotionInstrument reversed = redeemed.reverse(new Money("USD", 40), reason(), Instant.parse("2026-01-01T00:00:03Z"));
        assertThat(reversed.status()).isEqualTo(PromotionStatus.REVERSED);
        assertThatThrownBy(() -> reversed.reserve(new Money("USD",1), reason(), Instant.now())).isInstanceOf(DomainException.class);
    }
    static PromotionInstrument benefit() { return PromotionInstrument.issue("ben-1","acc-1","wac-1",BenefitType.BALANCE,BalanceType.PROMOTION_CREDIT,new Money("USD",100),IssuanceSource.MANUAL_OPS,null,new ApplicableScope("ANY_TRIP",null,"USD"),new RedemptionRule(false,null,false),new RevocationRule(null),Instant.parse("2026-01-01T00:00:00Z"),Instant.parse("2026-02-01T00:00:00Z"),reason(),Instant.parse("2026-01-01T00:00:00Z")); }
    static BusinessReason reason(){ return new BusinessReason(ReasonType.MANUAL_OPS,"TEST","MANUAL_ACTION","act-1",null); }
}
