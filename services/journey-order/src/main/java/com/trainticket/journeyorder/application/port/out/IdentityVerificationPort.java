package com.trainticket.journeyorder.application.port.out;

import com.trainticket.journeyorder.application.port.in.JourneyOrderRequest;

public interface IdentityVerificationPort {
    PreOrderCheckResult preOrderCheck(JourneyOrderRequest request, String orderIntentId, String idempotencyKey, String correlationId);
    default PreOrderCheckResult preOrderCheck(JourneyOrderRequest request, String orderIntentId, String idempotencyKey, String correlationId, java.time.Instant requestedAt) {
        return preOrderCheck(request,orderIntentId,idempotencyKey,correlationId);
    }
    default PreOrderCheckResult preOrderCheck(JourneyOrderRequest request, String orderIntentId, String idempotencyKey, String correlationId, java.time.Instant requestedAt, java.util.List<ResolvedOrderOffer.EligibilityUsage> eligibilityUsages) {
        if (!eligibilityUsages.isEmpty()) throw new UnsupportedOperationException("Eligibility reservations are not configured");
        return preOrderCheck(request,orderIntentId,idempotencyKey,correlationId,requestedAt);
    }
    default void confirmPreOrderCheck(String preOrderCheckId, String journeyOrderId, String idempotencyKey, String correlationId) {}
    default void releasePreOrderCheck(String preOrderCheckId, String releaseReason, String idempotencyKey, String correlationId) {}

    record PreOrderCheckResult(String result, String preOrderCheckId) {
        public boolean accepted() {
            return "PASS".equals(result);
        }
        public boolean manualReviewRequired() {
            return "MANUAL_REVIEW_REQUIRED".equals(result);
        }
        public boolean rejected() {
            return "REJECT".equals(result);
        }
        public boolean degraded() {
            return "DEGRADED".equals(result);
        }
    }
}
