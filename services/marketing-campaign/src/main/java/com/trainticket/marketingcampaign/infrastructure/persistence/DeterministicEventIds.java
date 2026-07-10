package com.trainticket.marketingcampaign.infrastructure.persistence;

import com.trainticket.platformkit.messaging.PrefixedIds;

final class DeterministicEventIds {
    private DeterministicEventIds() {}

    static String forTransition(String eventType, String aggregateId, long version) {
        return PrefixedIds.newEventId();
    }
}
