package com.trainticket.walletpromotion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trainticket.walletpromotion.domain.ApplicableScope;
import com.trainticket.walletpromotion.domain.BalanceType;
import com.trainticket.walletpromotion.domain.BenefitType;
import com.trainticket.walletpromotion.domain.BusinessReason;
import com.trainticket.walletpromotion.domain.IssuanceSource;
import com.trainticket.walletpromotion.domain.Money;
import com.trainticket.walletpromotion.domain.PromotionInstrument;
import com.trainticket.walletpromotion.domain.PromotionStatus;
import com.trainticket.walletpromotion.domain.ReasonType;
import com.trainticket.walletpromotion.domain.RedemptionRule;
import com.trainticket.walletpromotion.domain.RevocationRule;
import com.trainticket.walletpromotion.infrastructure.persistence.InMemoryPromotionRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class WalletPromotionServiceTest {
    @Test
    void redemptionIsIdempotentByBenefitAndBusinessReason() {
        WalletPromotionService service = service();
        PromotionInstrument benefit = service.issue(issue(Instant.parse("2999-02-01T00:00:00Z")));
        RedeemBenefitCommand command = new RedeemBenefitCommand(
            new Money("USD", 20),
            "ord-1",
            null,
            reason(ReasonType.ORDER_PURCHASE, "ORDER_BENEFIT_USE", "ORDER", "ord-1")
        );

        WalletPromotionService.RedeemResult first = service.redeem(benefit.benefitId(), command);
        WalletPromotionService.RedeemResult replay = service.redeem(benefit.benefitId(), command);

        assertThat(replay.redemption().redemptionId()).isEqualTo(first.redemption().redemptionId());
        assertThatThrownBy(() -> service.redeem(
            benefit.benefitId(),
            new RedeemBenefitCommand(new Money("USD", 10), "ord-1", null, command.businessReason())
        )).hasMessageContaining("benefit already redeemed");
    }

    @Test
    void expiryLeavesTerminalBenefitObservableAndAdjustsWallet() {
        WalletPromotionService service = service();
        PromotionInstrument benefit = service.issue(issue(Instant.parse("2000-01-01T00:00:00Z")));

        service.expireDueBenefits();

        assertThat(service.getBenefit(benefit.benefitId()).status()).isEqualTo(PromotionStatus.EXPIRED);
        assertThat(service.getWallet("acc-1").balances().getFirst().availableBalance().minorUnits()).isZero();
    }

    static WalletPromotionService service() {
        return new WalletPromotionService(new InMemoryPromotionRepository(Optional.empty()));
    }

    public static IssueBenefitCommand issue(Instant validUntil) {
        return new IssueBenefitCommand(
            "acc-1",
            BenefitType.BALANCE,
            BalanceType.PROMOTION_CREDIT,
            new Money("USD", 100),
            IssuanceSource.MANUAL_OPS,
            null,
            new ApplicableScope("ANY_TRIP", null, "USD"),
            new RedemptionRule(false, null, false),
            new RevocationRule(null),
            Instant.parse("1999-01-01T00:00:00Z"),
            validUntil,
            reason(ReasonType.MANUAL_OPS, "TEST", "MANUAL_ACTION", "act-1")
        );
    }

    public static BusinessReason reason(ReasonType type, String code, String referenceType, String referenceId) {
        return new BusinessReason(type, code, referenceType, referenceId, null);
    }
}
