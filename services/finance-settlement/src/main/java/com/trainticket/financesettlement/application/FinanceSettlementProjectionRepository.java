package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface FinanceSettlementProjectionRepository {
    Optional<FinanceSettlementEventHandler.PaymentCaptureFact> findCapture(String orderId);

    void saveCapture(String orderId, FinanceSettlementEventHandler.PaymentCaptureFact capture);

    Optional<Money> findApprovedRefund(String caseId);

    void saveApprovedRefund(String caseId, Money amount);

    default List<FinanceSettlementEventHandler.PaymentCaptureFact> findCapturesForSettlementDate(LocalDate settlementDate) { return List.of(); }

    default void saveChannelStatementLine(ChannelStatementLineProjection line) {}

    default List<ChannelStatementLineProjection> findChannelStatementLinesForSettlementDate(LocalDate settlementDate) { return List.of(); }

    default List<ChannelStatementProjection> findChannelStatementsForSettlementDate(LocalDate settlementDate) { return List.of(); }

    void saveBenefitCostEntry(BenefitCostEntry entry);

    Optional<BenefitCostEntry> findLatestBenefitCostEntryForBenefit(String benefitId);

    List<BenefitCostEntry> findBenefitCostEntries(String accountId, int limit, int offset);

    long countBenefitCostEntries(String accountId);

    default void saveChannelStatement(ChannelStatementProjection statement) {}

    default Optional<ChannelStatementProjection> findChannelStatement(String channelStatementId) { return Optional.empty(); }

    default List<ChannelStatementProjection> findChannelStatements(String channel, String statementDate, int limit, int offset) { return List.of(); }

    default long countChannelStatements(String channel, String statementDate) { return 0; }
}
