package com.trainticket.travelerprofile.domain;

import java.time.Instant;
import java.util.Map;

public record DocumentVerified(
    String profileId,
    String documentId,
    DocumentType documentType,
    String verifier,
    EventMetadata metadata
) implements TravelerProfileEvent {
    @Override
    public String eventId() { return metadata.eventId(); }
    @Override
    public Instant occurredAt() { return metadata.occurredAt(); }
    @Override
    public String sourceCommandId() { return metadata.sourceCommandId(); }
    @Override
    public String causationId() { return metadata.causationId(); }
    @Override
    public String correlationId() { return metadata.correlationId(); }
    @Override
    public int schemaVersion() { return metadata.schemaVersion(); }
    @Override
    public Map<String, String> attributes() { return metadata.attributes(); }
}
