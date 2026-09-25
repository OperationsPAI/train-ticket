package com.trainticket.walletpromotion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trainticket.walletpromotion.domain.ApplicableScope;
import com.trainticket.walletpromotion.domain.BalanceType;
import com.trainticket.walletpromotion.domain.BenefitScopeType;
import com.trainticket.walletpromotion.domain.BenefitType;
import com.trainticket.walletpromotion.domain.BenefitUseReference;
import com.trainticket.walletpromotion.domain.BenefitReservationStatus;
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
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class WalletPromotionServiceTest {
    @Test
    void findsTheCanonicalCompensationBenefitByCaseWithoutIssuingAgain() {
        InMemoryPromotionRepository repository = new InMemoryPromotionRepository(Optional.empty());
        WalletPromotionService service = new WalletPromotionService(repository, (benefit, useReference, businessReason) -> { });
        Instant now = Instant.parse("2026-09-25T04:00:00Z");
        IssueBenefitCommand command = new IssueBenefitCommand(
            "acc-1", BenefitType.COMPENSATION_CREDIT, BalanceType.PROMOTION_CREDIT, new Money("USD", 100),
            IssuanceSource.POST_SALES_COMP, "psc-case-1",
            new ApplicableScope(BenefitScopeType.ANY_TRIP, null, "USD"),
            new RedemptionRule(false, null, false), new RevocationRule(null), now, now.plus(Duration.ofDays(30)),
            reason(ReasonType.POST_SALES_COMP, "SERVICE_QUALITY_COMPLAINT", "POST_SALES_CASE", "psc-case-1")
        );
        PromotionInstrument issued = service.issue(command);

        assertThat(service.getCompensationByCaseId("psc-case-1")).isEqualTo(issued);
        assertThat(repository.countBenefits("acc-1", null, null)).isEqualTo(1);
    }

    @Test
    void redemptionIsIdempotentByBenefitAndRedemptionReference() {
        WalletPromotionService service = service();
        PromotionInstrument benefit = service.issue(issue(Instant.parse("2999-02-01T00:00:00Z")));
        RedeemBenefitCommand command = new RedeemBenefitCommand(
            new Money("USD", 20),
            "ord-1",
            null,
            use("ord-1"),
            reason(ReasonType.ORDER_PURCHASE, "ORDER_BENEFIT_USE", "ORDER", "ord-1")
        );

        WalletPromotionService.RedeemResult first = service.redeem(benefit.benefitId(), command);
        WalletPromotionService.RedeemResult replay = service.redeem(benefit.benefitId(), command);

        assertThat(replay.redemption().redemptionId()).isEqualTo(first.redemption().redemptionId());
        assertThatThrownBy(() -> service.redeem(
            benefit.benefitId(),
            new RedeemBenefitCommand(new Money("USD", 10), "ord-1", null, use("ord-1"), command.businessReason())
        )).hasMessageContaining("redemptionRef already has another redemption request");
        assertThatThrownBy(() -> service.redeem(
            benefit.benefitId(),
            new RedeemBenefitCommand(
                new Money("USD", 20), "ord-1", null, use("ord-1"),
                reason(ReasonType.ORDER_PURCHASE, "CHANGED_REASON", "ORDER", "ord-1")
            )
        )).hasMessageContaining("redemptionRef already has another redemption request");
    }

    @Test
    void expiryLeavesTerminalBenefitObservableAndAdjustsWallet() {
        WalletPromotionService service = service();
        PromotionInstrument benefit = service.issue(issue(Instant.parse("2000-01-01T00:00:00Z")));

        service.expireDueBenefits();

        assertThat(service.getBenefit(benefit.benefitId()).status()).isEqualTo(PromotionStatus.EXPIRED);
        assertThat(service.getWallet("acc-1").balances().getFirst().availableBalance().minorUnits()).isZero();
    }

    @Test
    void partialReservationRedemptionReleaseExpiryAndReversalKeepBalancesConsistent() {
        Instant start = Instant.parse("2026-09-25T02:00:00Z");
        MutableClock clock = new MutableClock(start);
        InMemoryPromotionRepository repository = new InMemoryPromotionRepository(Optional.empty());
        WalletPromotionService service = new WalletPromotionService(
            repository,
            (benefit, useReference, businessReason) -> { },
            clock
        );
        PromotionInstrument issued = service.issue(issueAt(start, start.plus(Duration.ofDays(1)), true));
        assertBalances(service, issued.benefitId(), 100, 0, 0);

        ReserveBenefitCommand reserve = new ReserveBenefitCommand(
            new Money("USD", 60), "res-1", start.plus(Duration.ofMinutes(5)), use("ord-1"),
            reason(ReasonType.ORDER_PURCHASE, "RESERVE-1", "ORDER", "ord-1")
        );
        PromotionInstrument reserved = service.reserve(issued.benefitId(), reserve);
        assertThat(reserved.status()).isEqualTo(PromotionStatus.RESERVED);
        assertBalances(service, issued.benefitId(), 40, 60, 0);
        long reserveVersion = reserved.version();
        assertThat(service.reserve(issued.benefitId(), reserve).version()).isEqualTo(reserveVersion);
        assertBalances(service, issued.benefitId(), 40, 60, 0);

        RedeemBenefitCommand redeem = new RedeemBenefitCommand(
            new Money("USD", 20), "red-1", "res-1", use("ord-1"),
            reason(ReasonType.ORDER_PURCHASE, "USE-1", "ORDER", "ord-1")
        );
        WalletPromotionService.RedeemResult redeemed = service.redeem(issued.benefitId(), redeem);
        assertThat(redeemed.benefit().status()).isEqualTo(PromotionStatus.RESERVED);
        assertBalances(service, issued.benefitId(), 40, 40, 20);
        long redeemVersion = redeemed.benefit().version();
        assertThat(service.redeem(issued.benefitId(), redeem).benefit().version()).isEqualTo(redeemVersion);
        assertBalances(service, issued.benefitId(), 40, 40, 20);

        ReleaseBenefitCommand release = new ReleaseBenefitCommand(
            new Money("USD", 10), "res-1", "rel-1",
            reason(ReasonType.RESERVATION_TIMEOUT, "RELEASE-1", "ORDER", "ord-1")
        );
        PromotionInstrument released = service.release(issued.benefitId(), release);
        assertThat(released.status()).isEqualTo(PromotionStatus.RESERVED);
        assertBalances(service, issued.benefitId(), 50, 30, 20);
        long releaseVersion = released.version();
        assertThat(service.release(issued.benefitId(), release).version()).isEqualTo(releaseVersion);

        clock.advance(Duration.ofMinutes(6));
        service.expireDueBenefits();
        assertBalances(service, issued.benefitId(), 80, 0, 20);
        assertThat(repository.findReservation(issued.benefitId(), "res-1").orElseThrow().status())
            .isEqualTo(BenefitReservationStatus.EXPIRED);
        long expiryVersion = service.getBenefit(issued.benefitId()).version();
        service.expireDueBenefits();
        assertThat(service.getBenefit(issued.benefitId()).version()).isEqualTo(expiryVersion);

        ReverseRedemptionCommand reverse = new ReverseRedemptionCommand(
            redeemed.redemption().redemptionId(), "rev-1", new Money("USD", 5),
            reason(ReasonType.REVERSAL, "REVERSAL-1", "ORDER", "ord-1")
        );
        WalletPromotionService.ReverseResult reversed = service.reverse(issued.benefitId(), reverse);
        assertThat(reversed.benefit().status()).isEqualTo(PromotionStatus.REVERSED);
        assertBalances(service, issued.benefitId(), 85, 0, 15);
        long reversalVersion = reversed.benefit().version();
        assertThat(service.reverse(issued.benefitId(), reverse).benefit().version()).isEqualTo(reversalVersion);
        assertBalances(service, issued.benefitId(), 85, 0, 15);
    }

    @Test
    void reversalAfterBenefitExpiryCorrectsRedeemedHistoryWithoutRestoringSpendableValue() {
        Instant start = Instant.parse("2026-09-25T03:00:00Z");
        MutableClock clock = new MutableClock(start);
        InMemoryPromotionRepository repository = new InMemoryPromotionRepository(Optional.empty());
        WalletPromotionService service = new WalletPromotionService(
            repository,
            (benefit, useReference, businessReason) -> { },
            clock
        );
        PromotionInstrument issued = service.issue(issueAt(start, start.plus(Duration.ofMinutes(10)), false));
        WalletPromotionService.RedeemResult redemption = service.redeem(
            issued.benefitId(),
            new RedeemBenefitCommand(
                new Money("USD", 20), "red-expired", null, use("ord-2"),
                reason(ReasonType.ORDER_PURCHASE, "USE-EXPIRED", "ORDER", "ord-2")
            )
        );
        assertBalances(service, issued.benefitId(), 80, 0, 20);

        clock.advance(Duration.ofMinutes(11));
        service.expireDueBenefits();
        assertThat(service.getBenefit(issued.benefitId()).status()).isEqualTo(PromotionStatus.EXPIRED);
        assertBalances(service, issued.benefitId(), 0, 0, 20);

        WalletPromotionService.ReverseResult reversed = service.reverse(
            issued.benefitId(),
            new ReverseRedemptionCommand(
                redemption.redemption().redemptionId(), "rev-expired", new Money("USD", 10),
                reason(ReasonType.REVERSAL, "REVERSAL-EXPIRED", "ORDER", "ord-2")
            )
        );
        assertThat(reversed.benefit().status()).isEqualTo(PromotionStatus.EXPIRED);
        assertBalances(service, issued.benefitId(), 0, 0, 10);
    }

    static WalletPromotionService service() {
        return new WalletPromotionService(
            new InMemoryPromotionRepository(Optional.empty()),
            (benefit, useReference, businessReason) -> { }
        );
    }

    public static IssueBenefitCommand issue(Instant validUntil) {
        return new IssueBenefitCommand(
            "acc-1",
            BenefitType.BALANCE,
            BalanceType.PROMOTION_CREDIT,
            new Money("USD", 100),
            IssuanceSource.MANUAL_OPS,
            null,
            new ApplicableScope(BenefitScopeType.ANY_TRIP, null, "USD"),
            new RedemptionRule(false, null, false),
            new RevocationRule(null),
            Instant.parse("1999-01-01T00:00:00Z"),
            validUntil,
            reason(ReasonType.MANUAL_OPS, "TEST", "MANUAL_ACTION", "act-1")
        );
    }

    private static IssueBenefitCommand issueAt(Instant validFrom, Instant validUntil, boolean requiresReservation) {
        return new IssueBenefitCommand(
            "acc-1", BenefitType.BALANCE, BalanceType.PROMOTION_CREDIT, new Money("USD", 100),
            IssuanceSource.MANUAL_OPS, null, new ApplicableScope(BenefitScopeType.ANY_TRIP, null, "USD"),
            new RedemptionRule(false, null, requiresReservation), new RevocationRule(null), validFrom, validUntil,
            reason(ReasonType.MANUAL_OPS, "TEST", "MANUAL_ACTION", "act-1")
        );
    }

    private static void assertBalances(WalletPromotionService service, String benefitId, long available, long reserved, long redeemed) {
        PromotionInstrument benefit = service.getBenefit(benefitId);
        var balance = service.getWallet(benefit.accountId()).balances().stream()
            .filter(candidate -> candidate.balanceType() == benefit.balanceType()
                && candidate.currency().equals(benefit.issuedAmount().currency()))
            .findFirst().orElseThrow();
        assertThat(benefit.availableAmount().minorUnits()).isEqualTo(available);
        assertThat(benefit.reservedAmount().minorUnits()).isEqualTo(reserved);
        assertThat(benefit.redeemedAmount().minorUnits()).isEqualTo(redeemed);
        assertThat(balance.availableBalance().minorUnits()).isEqualTo(available);
        assertThat(balance.reservedBalance().minorUnits()).isEqualTo(reserved);
        assertThat(balance.redeemedBalance().minorUnits()).isEqualTo(redeemed);
        assertThat(available + reserved + redeemed).isLessThanOrEqualTo(benefit.issuedAmount().minorUnits());
    }

    public static BusinessReason reason(ReasonType type, String code, String referenceType, String referenceId) {
        return new BusinessReason(type, code, referenceType, referenceId, null);
    }

    public static BenefitUseReference use(String orderId) {
        return new BenefitUseReference(orderId, null, java.util.List.of());
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) { this.current = current; }
        private void advance(Duration duration) { current = current.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
