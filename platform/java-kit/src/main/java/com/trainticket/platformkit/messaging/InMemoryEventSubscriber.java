package com.trainticket.platformkit.messaging;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class InMemoryEventSubscriber implements EventSubscriber {
    private final Set<String> consumed = ConcurrentHashMap.newKeySet();
    private final List<EventEnvelope> dlq = new ArrayList<>();
    private Function<EventEnvelope, HandlerResult> handler;

    @Override public void subscribe(List<String> streams, String group, String consumerName, Function<EventEnvelope, HandlerResult> handler) { this.handler = handler; }
    public HandlerResult receive(EventEnvelope envelope) { if (consumed.contains(envelope.eventId())) return null; HandlerResult result = handler.apply(envelope); if (result instanceof HandlerResult.Success) consumed.add(envelope.eventId()); if (result instanceof HandlerResult.FatalError) dlq.add(envelope); return result; }
    public boolean hasConsumed(String eventId) { return consumed.contains(eventId); }
    public List<EventEnvelope> dlq() { return List.copyOf(dlq); }
    public void reset() { consumed.clear(); dlq.clear(); }
    @Override public void shutdown() { handler = null; }
}
