package com.trainticket.journeyorder.application.port.out;

import com.trainticket.journeyorder.application.port.in.JourneyOrderRequest;

public interface IdentityVerificationPort {
    PreOrderCheckResult preOrderCheck(JourneyOrderRequest request, String orderIntentId, String idempotencyKey, String correlationId);
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
