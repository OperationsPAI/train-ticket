package com.trainticket.platformkit.messaging;

import java.util.List;

public interface EventSubscriber extends AutoCloseable {
    void subscribe(List<String> streams, String group, String consumerName, EventHandler handler) throws SubscribeFailedException;

    @Override
    void close();
}
