package com.trainticket.walletpromotion.application;

import com.trainticket.walletpromotion.domain.BenefitUseReference;
import com.trainticket.walletpromotion.domain.BusinessReason;
import com.trainticket.walletpromotion.domain.PromotionInstrument;

public interface BenefitScopeVerifier {
    void verify(PromotionInstrument benefit, BenefitUseReference useReference, BusinessReason businessReason);
}
