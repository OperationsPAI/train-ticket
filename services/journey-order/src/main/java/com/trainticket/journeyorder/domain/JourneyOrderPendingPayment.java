package com.trainticket.journeyorder.domain;

import java.time.Instant;
import java.util.Map;

public record JourneyOrderPendingPayment(String orderId, String accountId, String paymentPurpose, MonetarySummary monetarySummary, EventMetadata metadata) implements JourneyOrderEvent {
    public String eventId() { return metadata.eventId(); }
    public Instant occurredAt() { return metadata.occurredAt(); }
    public String sourceCommandId() { return metadata.sourceCommandId(); }
    public String causationId() { return metadata.causationId(); }
    public String correlationId() { return metadata.correlationId(); }
    public int schemaVersion() { return metadata.schemaVersion(); }
    public Map<String, String> attributes() { return metadata.attributes(); }
}
