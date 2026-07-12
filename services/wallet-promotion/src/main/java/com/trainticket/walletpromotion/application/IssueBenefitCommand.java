package com.trainticket.walletpromotion.application;

import com.trainticket.walletpromotion.domain.*;
import java.time.Instant;

public record IssueBenefitCommand(String accountId, BenefitType benefitType, BalanceType balanceType, Money amount, IssuanceSource issuanceSource, String caseId, ApplicableScope applicableScope, RedemptionRule redemptionRule, RevocationRule revocationRule, Instant validFrom, Instant validUntil, BusinessReason businessReason) {}
