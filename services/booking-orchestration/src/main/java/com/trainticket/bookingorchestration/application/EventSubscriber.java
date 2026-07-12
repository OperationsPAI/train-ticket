package com.trainticket.bookingorchestration.application;

@FunctionalInterface
public interface EventSubscriber {
    void subscribe(SubscriberConfig config);
}
