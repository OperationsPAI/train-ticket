package com.trainticket.travelerprofile.domain;

import java.time.Instant;
import java.util.Map;

public sealed interface TravelerProfileEvent permits
    TravelerProfileCreated,
    TravelerProfileActivated,
    TravelerProfileSuspended,
    TravelerProfileDeactivated,
    DocumentAdded,
    DocumentVerified,
    DocumentExpired,
    DocumentRevoked,
    PrimaryDocumentChanged,
    EligibilityGranted,
    EligibilityRevoked,
    EligibilityExpired,
    PreferencesUpdated {
    String eventId();
    Instant occurredAt();
    String profileId();
    String sourceCommandId();
    String causationId();
    String correlationId();
    int schemaVersion();
    Map<String, String> attributes();
    default String eventType() {
        return getClass().getSimpleName();
    }
}
