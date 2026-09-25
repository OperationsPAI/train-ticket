package com.trainticket.platformkit.messaging;

import java.util.List;
import java.util.Objects;

public interface RedisStreamOperations {
    void createGroup(String stream, String group);

    String publish(String stream, String envelopeJson);

    default void publishBatch(List<StreamMessage> messages) {
        for (StreamMessage message : messages) {
            publish(message.stream(), message.envelopeJson());
        }
    }

    List<StreamEntry> readGroup(String stream, String group, String consumerName);

    AutoClaimResult autoClaim(String stream, String group, String consumerName, String startId);

    int deliveryCount(String stream, String group, String messageId);

    void ack(String stream, String group, String messageId);

    default void pruneDeadConsumers(String stream, String group, String selfName, long maxIdleMillis) {}

    void moveToDlq(String stream, String envelopeJson, DlqMetadata metadata);

    record StreamMessage(String stream, String envelopeJson) {
    }

    record StreamEntry(String id, String envelopeJson) {
    }

    record AutoClaimResult(String nextStartId, List<StreamEntry> messages) {
        public AutoClaimResult {
            if (Objects.requireNonNull(nextStartId, "next auto-claim cursor is required").isBlank()) {
                throw new IllegalArgumentException("next auto-claim cursor must not be blank");
            }
            messages = List.copyOf(Objects.requireNonNull(messages, "auto-claimed messages are required"));
        }
    }
}
