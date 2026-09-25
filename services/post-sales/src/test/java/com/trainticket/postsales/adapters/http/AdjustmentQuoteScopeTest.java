package com.trainticket.postsales.adapters.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.trainticket.postsales.application.AdjustmentQuotePort.AdjustmentQuoteRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A change covers the travelers its case is scoped to, which is not always the
 * whole order.
 */
class AdjustmentQuoteScopeTest {
    private static final String TRAVELER_A = "tvl-01a0d74d-4cdd-706e-a632-4a2abd5ccc90";
    private static final String TRAVELER_B = "tvl-01a0d74d-a969-7f94-86fb-c65e272db313";

    @Test
    void aCaseScopedToOneTravelerOnlyRequiresThatTraveler() {
        // The resident failure: an order with two travelers, a change case
        // scoped to one of them, and an offer built for that one. Comparing the
        // offer against the whole order answered 500 on a change that was
        // exactly what the caller asked for.
        var request = new AdjustmentQuoteRequest("CHANGE", List.of("ent-1"), "ord-1", List.of("seg-1"),
            "case-1", "off-1", List.of(TRAVELER_A));
        var order = Map.<String, Object>of("travelerRefs", List.of(TRAVELER_A, TRAVELER_B));

        assertEquals(Set.of(TRAVELER_A), FarePricingAdjustmentQuoteClient.expectedTravelers(request, order));
    }

    @Test
    void withoutAScopeTheWholeOrderIsStillRequired() {
        var request = new AdjustmentQuoteRequest("CHANGE", List.of("ent-1"), "ord-1", List.of("seg-1"),
            "case-1", "off-1");
        var order = Map.<String, Object>of("travelerRefs", List.of(TRAVELER_A, TRAVELER_B));

        assertEquals(Set.of(TRAVELER_A, TRAVELER_B),
            FarePricingAdjustmentQuoteClient.expectedTravelers(request, order));
    }

    @Test
    void anEmptyOrderWithNoScopeYieldsNothingToMatch() {
        var request = new AdjustmentQuoteRequest("CHANGE", List.of("ent-1"), "ord-1", List.of("seg-1"), "case-1");

        assertEquals(Set.of(), FarePricingAdjustmentQuoteClient.expectedTravelers(request, Map.of()));
    }
}
