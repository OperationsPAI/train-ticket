package com.trainticket.journeyorder.application.port.in;

import java.util.Optional;

/**
 * Application service port for journey order operations.
 */
public interface JourneyOrderService {
    JourneyOrderResult createOrder(JourneyOrderRequest request, String idempotencyKey, String correlationId);
    Optional<JourneyOrderResult> getOrder(String orderId);
    OrderListResult listOrders(String accountId, String status, int limit, int offset);
    CancelJourneyOrderResult cancelOrder(CancelJourneyOrderRequest request, String idempotencyKey, String correlationId);
}
