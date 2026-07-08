package com.trainticket.walletpromotion.application;

import static org.assertj.core.api.Assertions.*;
import com.trainticket.walletpromotion.domain.*;
import com.trainticket.walletpromotion.infrastructure.persistence.InMemoryPromotionRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WalletPromotionServiceTest {
    @Test void redemptionIsIdempotentByBenefitAndBusinessReason() {
        WalletPromotionService svc = new WalletPromotionService(new InMemoryPromotionRepository(java.util.Optional.empty()));
        PromotionInstrument b = svc.issue(issue(Instant.parse("2999-02-01T00:00:00Z")));
        RedeemBenefitCommand cmd = new RedeemBenefitCommand(new Money("USD", 20), "ord-1", null, reason("ORDER_PURCHASE","ORDER_BENEFIT_USE","ORDER","ord-1"));
        var first = svc.redeem(b.benefitId(), cmd);
        var replay = svc.redeem(b.benefitId(), cmd);
        assertThat(replay.redemption().redemptionId()).isEqualTo(first.redemption().redemptionId());
        assertThatThrownBy(() -> svc.redeem(b.benefitId(), new RedeemBenefitCommand(new Money("USD", 10), "ord-1", null, cmd.businessReason()))).hasMessageContaining("benefit already redeemed");
    }
    @Test void expiryLeavesTerminalBenefitObservableAndAdjustsWallet() {
        WalletPromotionService svc = new WalletPromotionService(new InMemoryPromotionRepository(java.util.Optional.empty()));
        PromotionInstrument b = svc.issue(issue(Instant.parse("2000-01-01T00:00:00Z")));
        svc.expireDueBenefits();
        assertThat(svc.getBenefit(b.benefitId()).status()).isEqualTo(PromotionStatus.EXPIRED);
        assertThat(svc.getWallet("acc-1").balances().getFirst().availableBalance().minorUnits()).isZero();
    }
    static IssueBenefitCommand issue(Instant until){ return new IssueBenefitCommand("acc-1",BenefitType.BALANCE,BalanceType.PROMOTION_CREDIT,new Money("USD",100),IssuanceSource.MANUAL_OPS,null,new ApplicableScope("ANY_TRIP",null,"USD"),new RedemptionRule(false,null,false),new RevocationRule(null),Instant.parse("1999-01-01T00:00:00Z"),until,reason("MANUAL_OPS","TEST","MANUAL_ACTION","act-1")); }
    static BusinessReason reason(String t,String c,String rt,String rid){ return new BusinessReason(ReasonType.valueOf(t),c,rt,rid,null); }
}
