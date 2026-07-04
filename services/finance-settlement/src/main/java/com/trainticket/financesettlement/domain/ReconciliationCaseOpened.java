package com.trainticket.financesettlement.domain;

import java.time.Instant;
import java.util.Map;

public record ReconciliationCaseOpened(
    String reconciliationCaseId,
    String orderId,
    String paymentIntentId,
    String differenceType,
    Money expectedAmount,
    Money actualAmount,
    String description,
    EventMetadata metadata
) implements FinanceSettlementEvent {
    public String eventId() { return metadata.eventId(); }
    public Instant occurredAt() { return metadata.occurredAt(); }
    public String sourceCommandId() { return metadata.sourceCommandId(); }
    public String causationId() { return metadata.causationId(); }
    public String correlationId() { return metadata.correlationId(); }
    public int schemaVersion() { return metadata.schemaVersion(); }
    public Map<String, String> attributes() { return metadata.attributes(); }
}
