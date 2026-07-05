package com.trainticket.journeyorder.adapters;

import com.trainticket.journeyorder.application.port.out.EventSubscriber;
import com.trainticket.journeyorder.domain.EventEnvelope;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * In-memory fake implementation of EventSubscriber for unit tests.
 * Does NOT use Redis — tests run without a live Redis.
 */
public class InMemoryEventSubscriber implements EventSubscriber {

    private final List<EventEnvelope> received = new ArrayList<>();
    private final List<String> subscribedStreams = new ArrayList<>();
    private String group;
    private String consumerName;
    private Function<EventEnvelope, HandlerResult> handler;
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
        if (handler != null) {
            return handler.apply(envelope);
        }
        return new EventSubscriber.Success();
    }

    public List<EventEnvelope> received() {
        return List.copyOf(received);
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
