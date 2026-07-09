package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.Money;
import java.util.List;
import java.util.Optional;

public interface FinanceSettlementProjectionRepository {
    Optional<FinanceSettlementEventHandler.PaymentCaptureFact> findCapture(String orderId);

    void saveCapture(String orderId, FinanceSettlementEventHandler.PaymentCaptureFact capture);

    Optional<Money> findApprovedRefund(String caseId);

    void saveApprovedRefund(String caseId, Money amount);

    void saveBenefitCostEntry(BenefitCostEntry entry);

    Optional<BenefitCostEntry> findLatestBenefitCostEntryForBenefit(String benefitId);

    List<BenefitCostEntry> findBenefitCostEntries(String accountId, int limit, int offset);

    long countBenefitCostEntries(String accountId);
}
