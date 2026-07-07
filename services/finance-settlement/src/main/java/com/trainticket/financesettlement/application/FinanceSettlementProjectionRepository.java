package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.Money;
import java.util.Optional;

public interface FinanceSettlementProjectionRepository {
    Optional<FinanceSettlementEventHandler.PaymentCaptureFact> findCapture(String orderId);

    void saveCapture(String orderId, FinanceSettlementEventHandler.PaymentCaptureFact capture);

    Optional<Money> findApprovedRefund(String caseId);

    void saveApprovedRefund(String caseId, Money amount);
}
