package com.trainticket.financesettlement.application;

import com.trainticket.platformkit.messaging.EventEnvelope;
import com.trainticket.financesettlement.domain.DomainRuleViolation;
import com.trainticket.financesettlement.domain.FinanceSettlementEvent;
import com.trainticket.financesettlement.domain.ReconciliationCase;
import com.trainticket.financesettlement.domain.RevenueRecognition;
import java.util.List;

public class FinanceSettlementApplicationService {
    private final RevenueRecognitionRepository revenueRecognitions;
    private final ReconciliationCaseRepository reconciliationCases;
    private final EventPublisher eventPublisher;
    private final DomainEventEnvelopeMapper envelopeMapper;

    public FinanceSettlementApplicationService(
        RevenueRecognitionRepository revenueRecognitions,
        ReconciliationCaseRepository reconciliationCases,
        EventPublisher eventPublisher,
        DomainEventEnvelopeMapper envelopeMapper
    ) {
        this.revenueRecognitions = revenueRecognitions;
        this.reconciliationCases = reconciliationCases;
        this.eventPublisher = eventPublisher;
        this.envelopeMapper = envelopeMapper;
    }

    public RevenueRecognition getRevenueRecognition(String revenueRecognitionId) {
        return revenueRecognitions.findById(revenueRecognitionId)
            .orElseThrow(() -> new ResourceNotFoundException("revenue recognition not found"));
    }

    public ReconciliationCase getReconciliationCase(String reconciliationCaseId) {
        return reconciliationCases.findById(reconciliationCaseId)
            .orElseThrow(() -> new ResourceNotFoundException("reconciliation case not found"));
    }

    public Page<ReconciliationCase> listReconciliationCases(String orderId, int limit, int offset) {
        if (limit < 1 || limit > 100) {
            throw new ValidationException("limit must be between 1 and 100");
        }
        if (offset < 0) {
            throw new ValidationException("offset must not be negative");
        }
        List<ReconciliationCase> items = reconciliationCases.find(orderId, limit, offset);
        return new Page<>(items, reconciliationCases.count(orderId), limit, offset);
    }

    public void saveAndPublish(RevenueRecognition recognition) {
        revenueRecognitions.save(recognition);
        publish(recognition.domainEvents());
    }

    public void saveAndPublish(ReconciliationCase reconciliationCase) {
        reconciliationCases.save(reconciliationCase);
        publish(reconciliationCase.domainEvents());
    }

    private void publish(List<FinanceSettlementEvent> events) {
        for (FinanceSettlementEvent event : events) {
            try {
                eventPublisher.publish(envelopeMapper.toEnvelope(event));
            } catch (PublishFailedException ex) {
                throw ex;
            } catch (DomainRuleViolation ex) {
                throw ex;
            }
        }
    }

    public record Page<T>(List<T> items, long total, int limit, int offset) {}
}
