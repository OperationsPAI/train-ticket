package com.trainticket.platformkit.messaging;

import java.util.List;
import java.util.Objects;

public class InMemoryEventSubscriber implements EventSubscriber {
    private final InMemoryEventBus bus;
    private volatile boolean running;

    public InMemoryEventSubscriber(InMemoryEventBus bus) {
        this.bus = Objects.requireNonNull(bus, "bus is required");
    }

    @Override
    public void subscribe(List<String> streams, String group, String consumerName, EventHandler handler) {
        running = true;
        pollOnce(streams, group, handler);
    }

    public void pollOnce(List<String> streams, String group, EventHandler handler) {
        for (String stream : streams) {
            for (InMemoryEventBus.Delivery delivery : bus.read(stream, group, 10)) {
                handle(stream, group, delivery, handler);
            }
        }
    }

    public void recoverOnce(String stream, String group, EventHandler handler) {
        for (InMemoryEventBus.Delivery delivery : bus.claimPending(stream, group, 100)) {
            handle(stream, group, delivery, handler);
        }
    }

    private void handle(String stream, String group, InMemoryEventBus.Delivery delivery, EventHandler handler) {
        if (delivery.deliveryCount() >= RedisEventSubscriber.MAX_DELIVERY_ATTEMPTS) {
            bus.moveToDlq(stream, group, delivery.messageId());
            return;
        }
        EventEnvelope envelope = delivery.envelope();
        if (bus.wasConsumed(stream, group, envelope.eventId())) {
            bus.ack(stream, group, delivery.messageId());
            return;
        }
        HandlerResult result;
        try {
            result = handler.handle(envelope);
        } catch (RuntimeException exception) {
            return;
        }
        if (result == HandlerResult.SUCCESS) {
            bus.recordConsumed(stream, group, envelope.eventId());
            bus.ack(stream, group, delivery.messageId());
        } else if (result == HandlerResult.FATAL_FAILURE) {
            bus.moveToDlq(stream, group, delivery.messageId());
        }
    }

    public boolean running() {
        return running;
    }

    @Override
    public void close() {
        running = false;
    }
}
