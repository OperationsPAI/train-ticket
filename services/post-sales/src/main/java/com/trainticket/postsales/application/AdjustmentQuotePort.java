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
        String targetOfferId
    ) {
        public AdjustmentQuoteRequest(String purpose, List<String> entitlementIds, String journeyOrderId, List<String> segmentRefs, String idempotencyKey) {
            this(purpose, entitlementIds, journeyOrderId, segmentRefs, idempotencyKey, null);
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
