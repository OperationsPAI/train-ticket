package com.trainticket.postsales.domain;

import java.util.Objects;

public record ChangeFlowSnapshot(
    String changeOfferRef,
    String replacementCapacityHoldRef,
    ChangeFinancialAction financialAction,
    String oldEntitlementVoidRef,
    String newEntitlementIssueRef,
    String oldCapacityReleaseRef
) {
    public ChangeFlowSnapshot {
        changeOfferRef = PostSalesScope.requireText(changeOfferRef, "changeOfferRef");
        replacementCapacityHoldRef = PostSalesScope.requireText(replacementCapacityHoldRef, "replacementCapacityHoldRef");
        financialAction = Objects.requireNonNull(financialAction, "financialAction is required");
        oldEntitlementVoidRef = PostSalesScope.requireText(oldEntitlementVoidRef, "oldEntitlementVoidRef");
        newEntitlementIssueRef = PostSalesScope.requireText(newEntitlementIssueRef, "newEntitlementIssueRef");
        oldCapacityReleaseRef = PostSalesScope.requireText(oldCapacityReleaseRef, "oldCapacityReleaseRef");
    }
}
