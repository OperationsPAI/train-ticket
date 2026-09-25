package com.trainticket.walletpromotion.application;

import com.trainticket.walletpromotion.domain.*;
import com.trainticket.walletpromotion.metrics.WalletBalanceSample;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PromotionRepository {
    Optional<PromotionInstrument> findIssue(IssueBenefitCommand command);
    void recordIssue(IssueBenefitCommand command,String benefitId);
    Optional<PromotionInstrument> lockBenefit(String benefitId);
    Optional<PromotionInstrument> findBenefit(String benefitId);
    Optional<PromotionInstrument> findCompensationByCaseId(String caseId);
    Optional<WalletAccount> findWalletByAccountId(String accountId);
    List<PromotionInstrument> listBenefits(String accountId, PromotionStatus status, BenefitType benefitType, int limit, int offset);
    int countBenefits(String accountId, PromotionStatus status, BenefitType benefitType);
    List<PromotionInstrument> findExpirable(Instant now, int limit);
    List<BenefitReservation> findExpiredReservations(Instant now, int limit);
    List<BenefitReservation> findActiveReservations(String benefitId);
    Optional<BenefitReservation> findReservation(String benefitId, String reservationRef);
    Optional<BenefitRedemption> findRedemptionByReference(String benefitId, String redemptionRef);
    Optional<BenefitRedemption> findRedemption(String redemptionId);
    Optional<ReservationRelease> findReleaseByReference(String benefitId, String releaseRef);
    Optional<ReversalRecord> findReversalByReference(String redemptionId, String reversalRef);
    /**
     * Current balance levels per (state, currency), read from durable snapshots.
     * This is the source for {@code train_ticket.wallet.balance}, so it must not
     * depend on any in-process counter.
     */
    List<WalletBalanceSample> walletBalanceSnapshot();
    void saveMutation(PromotionInstrument benefit, WalletAccount wallet, WalletPromotionEvent event, MutationArtifacts artifacts);
}
