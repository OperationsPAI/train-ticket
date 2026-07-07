package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.Money;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryFinanceSettlementProjectionRepository implements FinanceSettlementProjectionRepository {
    private final ConcurrentMap<String, FinanceSettlementEventHandler.PaymentCaptureFact> capturesByOrderId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Money> approvedRefundsByCaseId = new ConcurrentHashMap<>();

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
}
