package com.trainticket.postsales.application;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port to Fare & Pricing's synchronous adjustment-quote API
 * (docs/08-contracts/api/fare-pricing.md, POST /api/v1/adjustment-quotes).
 * The production adapter resolves the purchased offer's original quote and propagates failures.
 */
public interface AdjustmentQuotePort {
    Optional<AdjustmentQuoteResult> compute(AdjustmentQuoteRequest request);

    record AdjustmentQuoteRequest(
        String purpose,
        List<String> entitlementIds,
        String journeyOrderId,
        List<String> segmentRefs,
        String idempotencyKey,
        String targetOfferId,
        /**
         * The travelers this case covers.
         *
         * A change can be scoped to one traveler of an order that carries
         * several -- the case and its target offer are built for that subset --
         * so the offer has to be checked against the case's scope rather than
         * against every traveler on the order.
         */
        List<String> travelerRefs
    ) {
        public AdjustmentQuoteRequest(String purpose, List<String> entitlementIds, String journeyOrderId, List<String> segmentRefs, String idempotencyKey) {
            this(purpose, entitlementIds, journeyOrderId, segmentRefs, idempotencyKey, null, null);
        }

        public AdjustmentQuoteRequest(String purpose, List<String> entitlementIds, String journeyOrderId, List<String> segmentRefs, String idempotencyKey, String targetOfferId) {
            this(purpose, entitlementIds, journeyOrderId, segmentRefs, idempotencyKey, targetOfferId, null);
        }
    }

    record AdjustmentQuoteResult(
        String adjustmentQuoteId,
        String status,
        long refundableMinorUnits,
        String refundableCurrency,
        long amountDueMinorUnits,
        String amountDueCurrency,
        PostSalesPolicyContext policyContext
    ) {
        public AdjustmentQuoteResult(String id,String status,long refundable,String refundableCurrency,long due,String dueCurrency) {
            this(id,status,refundable,refundableCurrency,due,dueCurrency,null);
        }
    }
}
