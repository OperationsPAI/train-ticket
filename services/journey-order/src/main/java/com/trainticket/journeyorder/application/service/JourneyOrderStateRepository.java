package com.trainticket.journeyorder.application.service;

import com.trainticket.journeyorder.application.port.in.CancelJourneyOrderResult;
import com.trainticket.journeyorder.application.port.in.JourneyOrderResult;
import com.trainticket.journeyorder.domain.AccountOrderState;
import com.trainticket.journeyorder.domain.JourneyOrder;
import java.util.List;
import java.util.Optional;

public interface JourneyOrderStateRepository {
    Optional<OrderManagementService.StoredOrder> findOrder(String orderId);
    List<OrderManagementService.StoredOrder> listOrders(String accountId, String status, int limit, int offset);
    long countOrders(String accountId, String status);
    void saveOrder(JourneyOrder order, String idempotencyKey);
    Optional<AccountOrderState> findAccountState(String accountId);
    void saveAccountState(String accountId, AccountOrderState state);
    Optional<OrderManagementService.IdempotencyEntry<JourneyOrderResult>> findCreateIdempotency(String key);
    void saveCreateIdempotency(String key, OrderManagementService.IdempotencyEntry<JourneyOrderResult> entry);
    Optional<OrderManagementService.IdempotencyEntry<CancelJourneyOrderResult>> findCancelIdempotency(String key);
    void saveCancelIdempotency(String key, OrderManagementService.IdempotencyEntry<CancelJourneyOrderResult> entry);
    boolean recordProcessedEvent(String eventId, String stream);
}
