package com.trainticket.platformkit.messaging;

public class InMemoryEventPublisher implements EventPublisher {
    private final InMemoryEventBus bus;

    public InMemoryEventPublisher(InMemoryEventBus bus) {
        this.bus = bus;
    }

    @Override
    public void publish(EventEnvelope envelope) {
        bus.publish(RedisStreamNames.streamForProducer(envelope.producer()), envelope);
    }
}
