package com.trainticket.journeyorder.application.service;

import com.trainticket.journeyorder.application.port.in.CancelJourneyOrderResult;
import com.trainticket.journeyorder.application.port.in.JourneyOrderResult;
import com.trainticket.journeyorder.domain.AccountOrderState;
import com.trainticket.journeyorder.domain.JourneyOrder;
import com.trainticket.journeyorder.domain.OrderItem;
import com.trainticket.platformkit.persistence.OptimisticConcurrencyException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Repository;

/**
 * In-memory stand-in for {@link com.trainticket.journeyorder.infrastructure.persistence.PostgresJourneyOrderStateRepository}.
 *
 * <p>This double mirrors the two properties the Postgres implementation relies on for correctness
 * under concurrent event handling, so behaviour does not silently diverge between the two:
 *
 * <ul>
 *   <li><b>Copy on read.</b> {@code JourneyOrder} and {@code OrderItem} are mutable. Postgres hands
 *       every reader a freshly rehydrated aggregate; handing out a shared instance here would let
 *       concurrent handlers mutate one another's state and would make the version check meaningless.</li>
 *   <li><b>Version-checked writes.</b> {@code saveOrder} applies the same optimistic concurrency rule
 *       as {@code SnapshotRepository.save}: the write only lands if the stored version still matches
 *       the version the caller read, otherwise {@link OptimisticConcurrencyException} is thrown.</li>
 * </ul>
 */
@Repository
@ConditionalOnMissingBean(JourneyOrderStateRepository.class)
public class InMemoryJourneyOrderStateRepository implements JourneyOrderStateRepository {
    private final Map<String, OrderManagementService.StoredOrder> orders = new ConcurrentHashMap<>();
    private final Map<String, AccountOrderState> accountStates = new ConcurrentHashMap<>();
    private final Map<String, OrderManagementService.IdempotencyEntry<JourneyOrderResult>> createIds = new ConcurrentHashMap<>();
    private final Map<String, OrderManagementService.IdempotencyEntry<CancelJourneyOrderResult>> cancelIds = new ConcurrentHashMap<>();
    private final Set<String> events = ConcurrentHashMap.newKeySet();
    private final Map<String, String> identityPreOrderChecks = new ConcurrentHashMap<>();

    @Override
    public Optional<OrderManagementService.StoredOrder> findOrder(String orderId) {
        return Optional.ofNullable(orders.get(orderId)).map(InMemoryJourneyOrderStateRepository::copyOf);
    }

    @Override
    public List<OrderManagementService.StoredOrder> findOrdersByTraveler(String travelerId) {
        return orders.values().stream()
            .filter(order -> order.order().travelers().stream().anyMatch(traveler -> traveler.travelerId().equals(travelerId)))
            .map(InMemoryJourneyOrderStateRepository::copyOf)
            .toList();
    }

    @Override
    public List<OrderManagementService.StoredOrder> listOrders(String accountId, String status, int limit, int offset) {
        return orders.values().stream()
            .filter(order -> accountId == null || accountId.isBlank() || order.order().accountId().equals(accountId))
            .filter(order -> status == null || status.isBlank() || OrderManagementService.toApiStatus(order.order()).equals(status))
            .skip(offset)
            .limit(limit)
            .map(InMemoryJourneyOrderStateRepository::copyOf)
            .toList();
    }

    @Override
    public long countOrders(String accountId, String status) {
        return listOrders(accountId, status, Integer.MAX_VALUE, 0).size();
    }

    @Override
    public void saveOrder(JourneyOrder order, String idempotencyKey) {
        long expectedVersion = order.version();
        OrderManagementService.StoredOrder written = orders.compute(order.orderId(), (id, current) -> {
            long currentVersion = current == null ? 0L : current.order().version();
            if (currentVersion != expectedVersion) {
                throw new OptimisticConcurrencyException("snapshot version conflict for " + id);
            }
            return copyOf(new OrderManagementService.StoredOrder(order, idempotencyKey), currentVersion + 1);
        });
        order.withVersion(written.order().version());
    }

    private static OrderManagementService.StoredOrder copyOf(OrderManagementService.StoredOrder stored) {
        return copyOf(stored, stored.order().version());
    }

    /** Deep copy via the domain rehydration API, mirroring the Postgres JSON round trip. */
    private static OrderManagementService.StoredOrder copyOf(OrderManagementService.StoredOrder stored, long version) {
        JourneyOrder order = stored.order();
        JourneyOrder copy = JourneyOrder.rehydrate(
            order.orderId(),
            order.accountId(),
            order.channelRef(),
            order.idempotencyKey(),
            order.offerSnapshot(),
            order.travelers(),
            order.segments(),
            copyOrderItems(order.orderItems()),
            order.state(),
            order.confirmationConditions(),
            order.timeline()
        );
        return new OrderManagementService.StoredOrder(copy.withVersion(version), stored.idempotencyKey());
    }

    private static List<OrderItem> copyOrderItems(List<OrderItem> items) {
        List<OrderItem> copies = new ArrayList<>(items.size());
        for (OrderItem item : items) {
            OrderItem copy = new OrderItem(
                item.orderItemId(),
                item.type(),
                item.description(),
                item.amount(),
                item.commercialReasonRef(),
                item.bindings()
            );
            if (item.cancelled()) {
                copy.cancel(item.cancellationReason());
            }
            copies.add(copy);
        }
        return copies;
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
    public Optional<String> findIdentityPreOrderCheckId(String orderId) {
        return Optional.ofNullable(identityPreOrderChecks.get(orderId));
    }

    @Override
    public void saveIdentityPreOrderCheckId(String orderId, String preOrderCheckId) {
        identityPreOrderChecks.put(orderId, preOrderCheckId);
    }

    @Override
    public boolean isEventProcessed(String eventId) {
        return events.contains(eventId);
    }

    @Override
    public boolean recordProcessedEvent(String eventId, String stream) {
        return events.add(eventId);
    }
}
