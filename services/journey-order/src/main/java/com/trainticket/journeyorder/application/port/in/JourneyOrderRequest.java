package com.trainticket.journeyorder.application.port.in;

import java.util.List;

public record JourneyOrderRequest(
    String accountId,
    String offerId,
    int offerVersion,
    List<String> travelerRefs,
    List<String> segmentRefs,
    String journeyDate,
    String productCode,
    String sourceIp
) {
    public JourneyOrderRequest(String accountId, String offerId, int offerVersion, List<String> travelerRefs, List<String> segmentRefs) {
        this(accountId, offerId, offerVersion, travelerRefs, segmentRefs, null, null, null);
    }

    public JourneyOrderRequest(String accountId, String offerId, int offerVersion, List<String> travelerRefs, List<String> segmentRefs, String journeyDate, String productCode) {
        this(accountId, offerId, offerVersion, travelerRefs, segmentRefs, journeyDate, productCode, null);
    }
}
