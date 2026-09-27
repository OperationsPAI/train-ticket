package com.trainticket.financesettlement.application;

import java.util.Optional;

public interface PaymentIntentOrderReferenceRepository {
    void save(String paymentIntentId, String orderReference);
    Optional<String> findOrderReference(String paymentIntentId);

    /**
     * The order for each of many intents at once.
     *
     * A channel statement is hundreds of thousands of lines, and resolving them
     * one query at a time is what makes reading it slow; the default keeps the
     * in-memory and stub implementations honest, and the SQL one asks once.
     * Intents with no stored reference are absent from the answer.
     */
    default java.util.Map<String, String> findOrderReferences(java.util.Collection<String> paymentIntentIds) {
        java.util.Map<String, String> found = new java.util.LinkedHashMap<>();
        for (String paymentIntentId : paymentIntentIds) {
            findOrderReference(paymentIntentId).ifPresent(order -> found.put(paymentIntentId, order));
        }
        return found;
    }
}
