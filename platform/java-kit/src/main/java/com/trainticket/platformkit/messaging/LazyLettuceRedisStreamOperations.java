package com.trainticket.platformkit.messaging;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.List;
import java.util.Objects;

final class LazyLettuceRedisStreamOperations implements RedisStreamOperations, AutoCloseable {
    private final RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private LettuceRedisStreamOperations delegate;

    LazyLettuceRedisStreamOperations(String redisUrl) {
        this.client = RedisClient.create(normalizedUrl(redisUrl));
    }

    @Override
    public void createGroup(String stream, String group) {
        operations().createGroup(stream, group);
    }

    @Override
    public String publish(String stream, String envelopeJson) {
        return operations().publish(stream, envelopeJson);
    }

    @Override
    public List<StreamEntry> readGroup(String stream, String group, String consumerName) {
        return operations().readGroup(stream, group, consumerName);
    }

    @Override
    public List<StreamEntry> autoClaim(String stream, String group, String consumerName) {
        return operations().autoClaim(stream, group, consumerName);
    }

    @Override
    public int deliveryCount(String stream, String group, String messageId) {
        return operations().deliveryCount(stream, group, messageId);
    }

    @Override
    public void ack(String stream, String group, String messageId) {
        operations().ack(stream, group, messageId);
    }

    @Override
    public void moveToDlq(String stream, String envelopeJson, DlqMetadata metadata) {
        operations().moveToDlq(stream, envelopeJson, metadata);
    }

    private synchronized LettuceRedisStreamOperations operations() {
        if (connection == null || !connection.isOpen()) {
            connection = client.connect();
            delegate = new LettuceRedisStreamOperations(connection);
        }
        return delegate;
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            connection.close();
            connection = null;
            delegate = null;
        }
        client.shutdown();
    }

    private static String normalizedUrl(String redisUrl) {
        String value = Objects.requireNonNullElse(redisUrl, "").trim();
        return value.isBlank() ? "redis://localhost:6379" : value;
    }
}
