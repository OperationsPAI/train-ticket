package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.Money;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryFinanceSettlementProjectionRepository implements FinanceSettlementProjectionRepository {
    private final ConcurrentMap<String, FinanceSettlementEventHandler.PaymentCaptureFact> capturesByOrderId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Money> approvedRefundsByCaseId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, BenefitCostEntry> benefitCostEntriesByEventId = new ConcurrentHashMap<>();

    @Override
    public Optional<FinanceSettlementEventHandler.PaymentCaptureFact> findCapture(String orderId) {
        return Optional.ofNullable(capturesByOrderId.get(orderId));
    }

    @Override
    public void saveCapture(String orderId, FinanceSettlementEventHandler.PaymentCaptureFact capture) {
        capturesByOrderId.put(orderId, capture);
    }

    @Override
    public Optional<Money> findApprovedRefund(String caseId) {
        return Optional.ofNullable(approvedRefundsByCaseId.get(caseId));
    }

    @Override
    public void saveApprovedRefund(String caseId, Money amount) {
        approvedRefundsByCaseId.put(caseId, amount);
    }

    @Override
    public void saveBenefitCostEntry(BenefitCostEntry entry) {
        benefitCostEntriesByEventId.put(entry.eventId(), entry);
    }

    @Override
    public Optional<BenefitCostEntry> findLatestBenefitCostEntryForBenefit(String benefitId) {
        return benefitCostEntriesByEventId.values().stream()
            .filter(entry -> benefitId.equals(entry.benefitId()))
            .max(Comparator.comparing(BenefitCostEntry::occurredAt).thenComparing(BenefitCostEntry::eventId));
    }

    @Override
    public List<BenefitCostEntry> findBenefitCostEntries(String accountId, int limit, int offset) {
        return benefitCostEntriesByEventId.values().stream()
            .filter(entry -> accountId == null || accountId.isBlank() || accountId.equals(entry.accountId()))
            .sorted(Comparator.comparing(BenefitCostEntry::occurredAt).reversed().thenComparing(BenefitCostEntry::eventId))
            .skip(offset)
            .limit(limit)
            .toList();
    }

    @Override
    public long countBenefitCostEntries(String accountId) {
        return benefitCostEntriesByEventId.values().stream()
            .filter(entry -> accountId == null || accountId.isBlank() || accountId.equals(entry.accountId()))
            .count();
    }
}
