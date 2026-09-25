package com.trainticket.marketingcampaign;

public record ServiceProfile(
    String serviceId,
    String domain,
    String language,
    String phase,
    String workPackage,
    String aggregates
) {}
