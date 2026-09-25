package com.trainticket.walletpromotion.application;

import com.trainticket.walletpromotion.domain.*;
import java.time.Instant;

public record IssueBenefitCommand(String accountId, BenefitType benefitType, BalanceType balanceType, Money amount, IssuanceSource issuanceSource, String caseId, String issuanceRef, ApplicableScope applicableScope, RedemptionRule redemptionRule, RevocationRule revocationRule, Instant validFrom, Instant validUntil, BusinessReason businessReason) {
    public IssueBenefitCommand(
        String accountId,
        BenefitType benefitType,
        BalanceType balanceType,
        Money amount,
        IssuanceSource issuanceSource,
        String caseId,
        ApplicableScope applicableScope,
        RedemptionRule redemptionRule,
        RevocationRule revocationRule,
        Instant validFrom,
        Instant validUntil,
        BusinessReason businessReason
    ) {
        this(accountId, benefitType, balanceType, amount, issuanceSource, caseId, null, applicableScope,
            redemptionRule, revocationRule, validFrom, validUntil, businessReason);
    }

    public String issuanceKey() {
        if (issuanceRef != null && !issuanceRef.isBlank()) return issuanceSource.name() + "|" + issuanceRef.trim();
        if (caseId != null && !caseId.isBlank()) return issuanceSource.name() + "|" + caseId.trim();
        return issuanceSource.name() + "|" + accountId + "|" + businessReason.key();
    }
}
