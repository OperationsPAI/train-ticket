package com.trainticket.journeyorder.adapters;

import com.trainticket.journeyorder.application.port.out.EventSubscriber;
import com.trainticket.platformkit.messaging.EventEnvelope;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * In-memory fake implementation of EventSubscriber for unit tests.
 * Does NOT use Redis — tests run without a live Redis.
 */
public class InMemoryEventSubscriber implements EventSubscriber {

    private static final int MAX_DELIVERY_ATTEMPTS = 5;

    private final List<EventEnvelope> received = new ArrayList<>();
    private final List<String> subscribedStreams = new ArrayList<>();
    private String group;
    private String consumerName;
    private Function<EventEnvelope, HandlerResult> handler;
    private final Set<String> processedEventIds = new HashSet<>();
    private final Map<String, Integer> deliveryCounts = new HashMap<>();
    private final List<EventEnvelope> dlq = new ArrayList<>();
    private volatile boolean running = false;
    private final CompletableFuture<Void> started = new CompletableFuture<>();

    @Override
    public void subscribe(List<String> streams, String group, String consumerName,
                          Function<EventEnvelope, HandlerResult> handler) throws SubscribeFailed {
        this.subscribedStreams.addAll(streams);
        this.group = group;
        this.consumerName = consumerName;
        this.handler = handler;
        this.running = true;
        started.complete(null);
    }

    @Override
    public void shutdown() {
        this.running = false;
    }

    /**
     * Simulate receiving an event through the subscriber.
     */
    public HandlerResult simulateReceive(EventEnvelope envelope) {
        received.add(envelope);
        int deliveries = deliveryCounts.merge(envelope.eventId(), 1, Integer::sum);
        if (processedEventIds.contains(envelope.eventId())) {
            return new EventSubscriber.Success();
        }
        if (deliveries >= MAX_DELIVERY_ATTEMPTS) {
            dlq.add(envelope);
            processedEventIds.add(envelope.eventId());
            return new EventSubscriber.FatalError("delivery attempts exhausted");
        }
        HandlerResult result = handler != null ? handler.apply(envelope) : new EventSubscriber.Success();
        if (result instanceof EventSubscriber.Success) {
            processedEventIds.add(envelope.eventId());
        } else if (result instanceof EventSubscriber.FatalError) {
            dlq.add(envelope);
            processedEventIds.add(envelope.eventId());
        }
        return result;
    }

    public List<EventEnvelope> received() {
        return List.copyOf(received);
    }

    public List<EventEnvelope> dlq() {
        return List.copyOf(dlq);
    }

    public List<String> subscribedStreams() {
        return List.copyOf(subscribedStreams);
    }

    public boolean isRunning() {
        return running;
    }

    public void waitForStart() {
        try {
            started.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Subscriber did not start", e);
        }
    }
}
