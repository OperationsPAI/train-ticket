package com.trainticket.marketingcampaign.infrastructure.persistence;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class DeterministicEventIds {
    private DeterministicEventIds() {}

    static String forTransition(String eventType, String aggregateId, long version) {
        return "evt-" + UUID.nameUUIDFromBytes((eventType + ":" + aggregateId + ":" + version).getBytes(StandardCharsets.UTF_8));
    }
}
