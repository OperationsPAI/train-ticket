package com.trainticket.payment.infrastructure.persistence;

import com.trainticket.platformkit.messaging.RedisStreamOperations;
import io.lettuce.core.Consumer;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisBusyException;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.models.stream.PendingMessage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class PublicLettuceRedisStreamOperations implements RedisStreamOperations {
    private final StatefulRedisConnection<String, String> connection;

    PublicLettuceRedisStreamOperations(StatefulRedisConnection<String, String> connection) {
        this.connection = Objects.requireNonNull(connection, "connection is required");
    }

    @Override
    public void createGroup(String stream, String group) {
        try {
            connection.sync().xgroupCreate(XReadArgs.StreamOffset.from(stream, "$"), group, XGroupCreateArgs.Builder.mkstream());
        } catch (RedisBusyException ignored) {
        }
    }

    @Override
    public String publish(String stream, String envelopeJson) {
        return connection.sync().xadd(stream, XAddArgs.Builder.maxlen(100_000).approximateTrimming(), Map.of("d", envelopeJson));
    }

    @Override
    public List<StreamEntry> readGroup(String stream, String group, String consumerName) {
        return connection.sync()
            .xreadgroup(Consumer.from(group, consumerName), XReadArgs.Builder.block(Duration.ofSeconds(2)).count(10), XReadArgs.StreamOffset.lastConsumed(stream))
            .stream()
            .map(PublicLettuceRedisStreamOperations::toEntry)
            .toList();
    }

    @Override
    public List<StreamEntry> autoClaim(String stream, String group, String consumerName) {
        return connection.sync()
            .xautoclaim(stream, XAutoClaimArgs.Builder.xautoclaim(Consumer.from(group, consumerName), Duration.ofSeconds(60), "0-0").count(100))
            .getMessages()
            .stream()
            .map(PublicLettuceRedisStreamOperations::toEntry)
            .toList();
    }

    @Override
    public int deliveryCount(String stream, String group, String messageId) {
        List<PendingMessage> messages = connection.sync().xpending(stream, group, Range.create(messageId, messageId), Limit.create(0, 1));
        if (messages.isEmpty()) {
            return 1;
        }
        long redeliveryCount = messages.getFirst().getRedeliveryCount();
        return redeliveryCount >= Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(1, (int) redeliveryCount);
    }

    @Override
    public void ack(String stream, String group, String messageId) {
        connection.sync().xack(stream, group, messageId);
    }

    @Override
    public void moveToDlq(String stream, String envelopeJson) {
        connection.sync().xadd(com.trainticket.platformkit.messaging.RedisStreamNames.dlqFor(stream), XAddArgs.Builder.maxlen(100_000).approximateTrimming(), Map.of("d", envelopeJson));
    }

    private static StreamEntry toEntry(StreamMessage<String, String> message) {
        String body = message.getBody().get("d");
        if (body == null) {
            body = message.getBody().get("envelope");
        }
        return new StreamEntry(message.getId(), body);
    }
}
