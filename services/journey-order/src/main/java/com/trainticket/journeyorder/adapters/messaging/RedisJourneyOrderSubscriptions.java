package com.trainticket.journeyorder.adapters.messaging;

import java.util.List;

public final class RedisJourneyOrderSubscriptions {
    private static final List<String> STREAMS = List.of(
        "events:offer-management",
        "events:payment",
        "events:post-sales",
        "events:traveler-profile",
        "events:risk-compliance",
        "events:account",
        "events:entitlement-ticketing",
        "events:ancillary-service",
        "events:identity-verification",
        "events:transfer-management"
    );
    private static final String GROUP = "journey-order";

    private RedisJourneyOrderSubscriptions() {
    }

    public static List<String> streams() {
        return STREAMS;
    }

    public static String group() {
        return GROUP;
    }
}
