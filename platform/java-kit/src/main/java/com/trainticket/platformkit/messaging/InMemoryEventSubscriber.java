package com.trainticket.platformkit.messaging;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class InMemoryEventSubscriber implements EventSubscriber {
    static final int MAX_DELIVERY_ATTEMPTS = 5;

    private final Set<String> consumed = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> deliveryCounts = new ConcurrentHashMap<>();
    private final List<EventEnvelope> dlq = new ArrayList<>();
    private Function<EventEnvelope, HandlerResult> handler;

    @Override
    public void subscribe(List<String> streams, String group, String consumerName, Function<EventEnvelope, HandlerResult> handler) {
        if (streams == null || streams.isEmpty()) throw new SubscribeFailed("at least one stream is required", null);
        this.handler = Objects.requireNonNull(handler, "handler is required");
    }

    public HandlerResult receive(EventEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope is required");
        if (consumed.contains(envelope.eventId())) return new HandlerResult.Success();
        int attempts = deliveryCounts.merge(envelope.eventId(), 1, Integer::sum);
        if (attempts >= MAX_DELIVERY_ATTEMPTS) {
            dlq.add(envelope);
            return new HandlerResult.FatalError("delivery attempts exhausted");
        }
        HandlerResult result = handler == null ? new HandlerResult.Success() : handler.apply(envelope);
        if (result instanceof HandlerResult.Success) consumed.add(envelope.eventId());
        if (result instanceof HandlerResult.FatalError) dlq.add(envelope);
        return result;
    }

    public int deliveryCount(String eventId) { return deliveryCounts.getOrDefault(eventId, 0); }
    public boolean hasConsumed(String eventId) { return consumed.contains(eventId); }
    public List<EventEnvelope> dlq() { return List.copyOf(dlq); }
    public void reset() { consumed.clear(); deliveryCounts.clear(); dlq.clear(); }
    @Override public void shutdown() { handler = null; }
}
