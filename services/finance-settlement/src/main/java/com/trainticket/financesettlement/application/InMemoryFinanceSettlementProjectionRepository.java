package com.trainticket.financesettlement.application;

import com.trainticket.financesettlement.domain.Money;
import java.time.LocalDate;
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
    private final ConcurrentMap<String, AncillaryFinancialFact> ancillaryFinancialFactsByEventId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ChannelStatementProjection> channelStatementsById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ChannelStatementLineProjection> channelStatementLinesById = new ConcurrentHashMap<>();

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
    public List<FinanceSettlementEventHandler.PaymentCaptureFact> findCapturesForSettlementDate(LocalDate settlementDate) {
        return capturesByOrderId.values().stream()
            .filter(capture -> settlementDate.equals(capture.occurredAt().atZone(java.time.ZoneOffset.UTC).toLocalDate()))
            .toList();
    }

    @Override
    public void saveChannelStatementLine(ChannelStatementLineProjection line) {
        channelStatementLinesById.put(line.statementLineId(), line);
    }

    @Override
    public List<ChannelStatementLineProjection> findChannelStatementLinesForSettlementDate(LocalDate settlementDate) {
        return channelStatementLinesById.values().stream()
            .filter(line -> settlementDate.toString().equals(line.statementDate()))
            .sorted(Comparator.comparing(ChannelStatementLineProjection::statementLineId))
            .toList();
    }

    @Override
    public List<ChannelStatementProjection> findChannelStatementsForSettlementDate(LocalDate settlementDate) {
        return channelStatementsById.values().stream()
            .filter(statement -> settlementDate.toString().equals(statement.statementDate()))
            .toList();
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

    @Override
    public void saveAncillaryFinancialFact(AncillaryFinancialFact fact) {
        ancillaryFinancialFactsByEventId.put(fact.eventId(), fact);
    }

    @Override
    public Optional<AncillaryFinancialFact> findAncillaryFinancialFact(String eventId) {
        return Optional.ofNullable(ancillaryFinancialFactsByEventId.get(eventId));
    }

    @Override
    public List<AncillaryFinancialFact> findAncillaryFinancialFacts(String journeyOrderId, int limit, int offset) {
        return ancillaryFinancialFactsByEventId.values().stream()
            .filter(fact -> journeyOrderId == null || journeyOrderId.isBlank() || journeyOrderId.equals(fact.journeyOrderId()))
            .sorted(Comparator.comparing(AncillaryFinancialFact::occurredAt).reversed().thenComparing(AncillaryFinancialFact::eventId))
            .skip(offset)
            .limit(limit)
            .toList();
    }

    @Override
    public long countAncillaryFinancialFacts(String journeyOrderId) {
        return ancillaryFinancialFactsByEventId.values().stream()
            .filter(fact -> journeyOrderId == null || journeyOrderId.isBlank() || journeyOrderId.equals(fact.journeyOrderId()))
            .count();
    }

    @Override
    public void saveChannelStatement(ChannelStatementProjection statement) {
        channelStatementsById.put(statement.channelStatementId(), statement);
    }

    @Override
    public Optional<ChannelStatementProjection> findChannelStatement(String channelStatementId) {
        return Optional.ofNullable(channelStatementsById.get(channelStatementId));
    }

    @Override
    public List<ChannelStatementProjection> findChannelStatements(String channel, String statementDate, int limit, int offset) {
        return channelStatementsById.values().stream()
            .filter(statement -> channel == null || channel.isBlank() || channel.equals(statement.channel()))
            .filter(statement -> statementDate == null || statementDate.isBlank() || statementDate.equals(statement.statementDate()))
            .sorted(Comparator.comparing(ChannelStatementProjection::statementDate).reversed().thenComparing(ChannelStatementProjection::channelStatementId))
            .skip(offset)
            .limit(limit)
            .toList();
    }

    @Override
    public long countChannelStatements(String channel, String statementDate) {
        return channelStatementsById.values().stream()
            .filter(statement -> channel == null || channel.isBlank() || channel.equals(statement.channel()))
            .filter(statement -> statementDate == null || statementDate.isBlank() || statementDate.equals(statement.statementDate()))
            .count();
    }
}
