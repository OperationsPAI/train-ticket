package com.trainticket.platformkit.messaging;

import java.util.List;

public interface RedisStreamOperations {
    void createGroup(String stream, String group);

    String publish(String stream, String envelopeJson);

    default void publishBatch(List<StreamMessage> messages) {
        for (StreamMessage message : messages) {
            publish(message.stream(), message.envelopeJson());
        }
    }

    List<StreamEntry> readGroup(String stream, String group, String consumerName);

    List<StreamEntry> autoClaim(String stream, String group, String consumerName);

    int deliveryCount(String stream, String group, String messageId);

    void ack(String stream, String group, String messageId);

    default void pruneDeadConsumers(String stream, String group, String selfName, long maxIdleMillis) {}

    void moveToDlq(String stream, String envelopeJson, DlqMetadata metadata);

    record StreamMessage(String stream, String envelopeJson) {
    }

    record StreamEntry(String id, String envelopeJson) {
    }
}
