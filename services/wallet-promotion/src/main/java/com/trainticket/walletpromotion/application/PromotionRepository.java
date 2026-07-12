package com.trainticket.walletpromotion.application;

import com.trainticket.walletpromotion.domain.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PromotionRepository {
    Optional<PromotionInstrument> findBenefit(String benefitId);
    Optional<WalletAccount> findWalletByAccountId(String accountId);
    List<PromotionInstrument> listBenefits(String accountId, PromotionStatus status, BenefitType benefitType, int limit, int offset);
    int countBenefits(String accountId, PromotionStatus status, BenefitType benefitType);
    List<PromotionInstrument> findExpirable(Instant now, int limit);
    Optional<BenefitRedemption> findRedemptionByReason(String benefitId, BusinessReason reason);
    Optional<BenefitRedemption> findRedemption(String redemptionId);
    void saveMutation(PromotionInstrument benefit, WalletAccount wallet, WalletPromotionEvent event, BenefitRedemption redemption, ReversalRecord reversal);
}
