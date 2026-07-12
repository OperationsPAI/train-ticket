package com.trainticket.postsales.domain;

public record ChangeApplied(
    String caseId,
    String journeyOrderId,
    String oldEntitlementRef,
    String newEntitlementRef,
    String changeOfferRef,
    EventMetadata metadata
) implements PostSalesEvent { }
