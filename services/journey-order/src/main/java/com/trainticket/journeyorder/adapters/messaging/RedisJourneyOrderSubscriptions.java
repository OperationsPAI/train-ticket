package com.trainticket.journeyorder.adapters.messaging;

import java.util.List;

public final class RedisJourneyOrderSubscriptions {
    private static final List<String> STREAMS = List.of(
        "events:booking-orchestration",
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

    /**
     * Event types OrderManagementService.handle actually acts on.
     *
     * Used to skip the handler entirely for everything else. That matters because
     * handle() is @Transactional: Spring opens a database transaction on entry and
     * the method reads and writes processed_events around its switch, so an event
     * type whose branch is `new Success()` still costs a full transaction. With
     * handler timing instrumented those no-op types measured a p50 of 49 ms each,
     * on all 11 subscribed streams.
     *
     * MUST stay in step with that switch. A type listed here but unhandled costs a
     * pointless transaction; a type MISSING from here that the switch handles is
     * silently dropped, which is far worse.
     * RedisJourneyOrderSubscriptionsActionableTest reads the switch out of the
     * service source and checks this set covers it.
     */
    public static boolean isActionable(String eventType) {
        return ACTIONABLE.contains(eventType);
    }

    private static final java.util.Set<String> ACTIONABLE = java.util.Set.of(
        "PaymentCaptured",
        // Intent expiry reaches the order as PaymentTimedOut, not
        // PaymentIntentExpired: only PaymentTimedOut carries the order
        // reference this handler resolves the aggregate by. PaymentExpired is
        // the name in the older docs and in notification's templates; it is
        // accepted too so the two spellings cannot diverge into a silent drop.
        "PaymentTimedOut",
        "PaymentExpired",
        "PostSalesApplied",
        "RiskAssessmentResult",
        "RiskBlockApplied",
        "RiskBlockLifted",
        "AccountCreated",
        "AccountFrozen",
        "AccountUnfrozen",
        "AccountClosureStarted",
        "AccountClosed",
        "EntitlementIssued",
        "AncillaryOrderItemSelected",
        "AncillaryOrderItemPendingConfirmation",
        "AncillaryOrderItemConfirmed",
        "AncillaryOrderItemFulfillmentReady",
        "AncillaryOrderItemFulfilled",
        "AncillaryOrderItemFailed",
        "AncillaryOrderItemRefunded",
        "AncillaryOrderItemCancelled",
        "ConnectionContractConfirmed",
        "ConnectionMissed",
        "ConnectionRecovered",
        "CredentialRegistered",
        "VerificationCaseStarted",
        "VerificationPassed",
        "VerificationFailed",
        "EligibilityCertificateRegistered",
        "EligibilityCertificateVerified",
        "EligibilityUsageReserved",
        "EligibilityUsageConfirmed",
        "EligibilityUsageReleased"
    );
}
