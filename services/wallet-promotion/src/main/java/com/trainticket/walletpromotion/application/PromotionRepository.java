package com.trainticket.walletpromotion.application;

import com.trainticket.walletpromotion.domain.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PromotionRepository {
    Optional<PromotionInstrument> findCompensationIssue(IssueBenefitCommand command);
    void recordCompensationIssue(IssueBenefitCommand command,String benefitId);
    Optional<PromotionInstrument> findBenefit(String benefitId);
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
    void saveMutation(PromotionInstrument benefit, WalletAccount wallet, WalletPromotionEvent event, MutationArtifacts artifacts);
}
