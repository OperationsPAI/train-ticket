package com.trainticket.marketingcampaign.infrastructure.messaging;

import java.util.List;

public final class MarketingCampaignSubscriptions {
    private static final List<String> STREAMS = List.of("events:journey-order");
    private static final String GROUP = "marketing-campaign";

    private MarketingCampaignSubscriptions() {
    }

    public static List<String> streams() {
        return STREAMS;
    }

    public static String group() {
        return GROUP;
    }
}
