package com.trainticket.journeyorder.application.service;

import com.trainticket.journeyorder.application.port.in.CancelJourneyOrderResult;
import com.trainticket.journeyorder.application.port.in.JourneyOrderResult;
import com.trainticket.journeyorder.domain.AccountOrderState;
import com.trainticket.journeyorder.domain.JourneyOrder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnMissingBean(JourneyOrderStateRepository.class)
public class InMemoryJourneyOrderStateRepository implements JourneyOrderStateRepository {
    private final Map<String, OrderManagementService.StoredOrder> orders = new LinkedHashMap<>();
    private final Map<String, AccountOrderState> accountStates = new ConcurrentHashMap<>();
    private final Map<String, OrderManagementService.IdempotencyEntry<JourneyOrderResult>> createIds = new ConcurrentHashMap<>();
    private final Map<String, OrderManagementService.IdempotencyEntry<CancelJourneyOrderResult>> cancelIds = new ConcurrentHashMap<>();
    private final Set<String> events = ConcurrentHashMap.newKeySet();

    @Override
    public Optional<OrderManagementService.StoredOrder> findOrder(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }

    @Override
    public List<OrderManagementService.StoredOrder> listOrders(String accountId, String status, int limit, int offset) {
        return orders.values().stream()
            .filter(order -> accountId == null || accountId.isBlank() || order.order().accountId().equals(accountId))
            .filter(order -> status == null || status.isBlank() || OrderManagementService.toApiStatus(order.order()).equals(status))
            .skip(offset)
            .limit(limit)
            .toList();
    }

    @Override
    public long countOrders(String accountId, String status) {
        return listOrders(accountId, status, Integer.MAX_VALUE, 0).size();
    }

    @Override
    public void saveOrder(JourneyOrder order, String idempotencyKey) {
        orders.put(order.orderId(), new OrderManagementService.StoredOrder(order, idempotencyKey));
    }

    @Override
    public Optional<AccountOrderState> findAccountState(String accountId) {
        return Optional.ofNullable(accountStates.get(accountId));
    }

    @Override
    public void saveAccountState(String accountId, AccountOrderState state) {
        accountStates.put(accountId, state);
    }

    @Override
    public Optional<OrderManagementService.IdempotencyEntry<JourneyOrderResult>> findCreateIdempotency(String key) {
        return Optional.ofNullable(createIds.get(key));
    }

    @Override
    public void saveCreateIdempotency(String key, OrderManagementService.IdempotencyEntry<JourneyOrderResult> entry) {
        createIds.put(key, entry);
    }

    @Override
    public Optional<OrderManagementService.IdempotencyEntry<CancelJourneyOrderResult>> findCancelIdempotency(String key) {
        return Optional.ofNullable(cancelIds.get(key));
    }

    @Override
    public void saveCancelIdempotency(String key, OrderManagementService.IdempotencyEntry<CancelJourneyOrderResult> entry) {
        cancelIds.put(key, entry);
    }

    @Override
    public boolean recordProcessedEvent(String eventId, String stream) {
        return events.add(eventId);
    }
}
