package com.trainticket.payment.adapters.messaging;

import java.util.List;

interface RedisStreamOperations {
    void createGroup(String stream, String group);

    List<StreamEntry> readGroup(String stream, String group, String consumerName);

    List<StreamEntry> autoClaim(String stream, String group, String consumerName);

    int deliveryCount(String stream, String group, String messageId);

    void ack(String stream, String group, String messageId);

    void moveToDlq(String stream, String envelopeJson);

    record StreamEntry(String id, String envelopeJson) {
    }
}
