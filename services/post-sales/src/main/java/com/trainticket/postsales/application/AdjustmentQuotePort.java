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
        String idempotencyKey
    ) { }

    record AdjustmentQuoteResult(
        String adjustmentQuoteId,
        String status,
        long refundableMinorUnits,
        String refundableCurrency,
        long amountDueMinorUnits,
        String amountDueCurrency
    ) { }
}
