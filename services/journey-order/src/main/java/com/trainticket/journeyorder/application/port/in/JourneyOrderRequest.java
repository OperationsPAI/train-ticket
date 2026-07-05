package com.trainticket.journeyorder.application.port.in;

import java.util.List;

public record JourneyOrderRequest(
    String accountId,
    String offerId,
    int offerVersion,
    List<String> travelerRefs,
    List<String> segmentRefs
) {}
