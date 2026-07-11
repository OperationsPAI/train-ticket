package com.trainticket.platformkit.messaging;

import io.lettuce.core.Consumer;
import io.lettuce.core.LettuceFutures;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisBusyException;
import io.lettuce.core.RedisFuture;
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
import java.util.concurrent.ExecutionException;
import org.slf4j.LoggerFactory;

public final class LettuceRedisStreamOperations implements RedisStreamOperations {
    private static final long BLOCK_MS = Long.parseLong(System.getenv().getOrDefault("CONSUMER_BLOCK_MS", "100"));
    private static final int BATCH_COUNT = Integer.parseInt(System.getenv().getOrDefault("CONSUMER_BATCH_COUNT", "100"));

    private final StatefulRedisConnection<String, String> connection;

    public LettuceRedisStreamOperations(StatefulRedisConnection<String, String> connection) {
        this.connection = Objects.requireNonNull(connection, "connection is required");
    }

    @Override
    public void createGroup(String stream, String group) {
        try {
            connection.sync().xgroupCreate(XReadArgs.StreamOffset.from(stream, "$"), group, XGroupCreateArgs.Builder.mkstream());
        } catch (RedisBusyException ignored) {
            // Existing group is safe on restart.
        }
    }

    @Override
    public String publish(String stream, String envelopeJson) {
        return connection.sync().xadd(stream, XAddArgs.Builder.maxlen(100_000).approximateTrimming(), Map.of("envelope", envelopeJson));
    }

    @Override
    public void publishBatch(List<RedisStreamOperations.StreamMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }

        var async = connection.async();
        connection.setAutoFlushCommands(false);
        List<RedisFuture<String>> futures;
        try {
            futures = messages.stream()
                .map(message -> async.xadd(
                    message.stream(),
                    XAddArgs.Builder.maxlen(100_000).approximateTrimming(),
                    Map.of("envelope", message.envelopeJson())
                ))
                .toList();
            connection.flushCommands();
        } finally {
            connection.setAutoFlushCommands(true);
        }
        if (!LettuceFutures.awaitAll(Duration.ofSeconds(10), futures.toArray(RedisFuture[]::new))) {
            throw new IllegalStateException("timed out while publishing outbox batch to Redis");
        }
        for (RedisFuture<String> future : futures) {
            try {
                future.get();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while publishing outbox batch to Redis", error);
            } catch (ExecutionException error) {
                throw new IllegalStateException("failed to publish outbox batch to Redis", error.getCause());
            }
        }
    }

    @Override
    public List<StreamEntry> readGroup(String stream, String group, String consumerName) {
        return connection.sync()
            .xreadgroup(Consumer.from(group, consumerName), XReadArgs.Builder.block(Duration.ofMillis(BLOCK_MS)).count(BATCH_COUNT), XReadArgs.StreamOffset.lastConsumed(stream))
            .stream()
            .map(LettuceRedisStreamOperations::toEntry)
            .toList();
    }

    @Override
    public List<StreamEntry> autoClaim(String stream, String group, String consumerName) {
        return connection.sync()
            .xautoclaim(stream, XAutoClaimArgs.Builder.xautoclaim(Consumer.from(group, consumerName), Duration.ofSeconds(60), "0-0").count(100))
            .getMessages()
            .stream()
            .map(LettuceRedisStreamOperations::toEntry)
            .toList();
    }

    @Override
    public int deliveryCount(String stream, String group, String messageId) {
        List<PendingMessage> messages = connection.sync().xpending(stream, group, Range.create(messageId, messageId), Limit.create(0, 1));
        if (messages.isEmpty()) {
            return 1;
        }
        long redeliveryCount = messages.getFirst().getRedeliveryCount();
        if (redeliveryCount >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, (int) redeliveryCount);
    }

    @Override
    public void ack(String stream, String group, String messageId) {
        connection.sync().xack(stream, group, messageId);
    }

    @Override
    public void pruneDeadConsumers(String stream, String group, String selfName, long maxIdleMillis) {
        try {
            List<Object> consumers = connection.sync().xinfoConsumers(stream, group);
            for (Object consumer : consumers) {
                ConsumerInfo info = ConsumerInfo.from(consumer);
                if (info.name().equals(selfName)) {
                    continue;
                }
                if (info.idleMillis() > maxIdleMillis) {
                    connection.sync().xgroupDelconsumer(stream, Consumer.from(group, info.name()));
                    LoggerFactory.getLogger(LettuceRedisStreamOperations.class)
                        .info("pruned dead consumer {} from {}/{} (idle={}ms, pending={})",
                            info.name(), stream, group, info.idleMillis(), info.pending());
                }
            }
        } catch (RuntimeException ignored) {
            // best-effort cleanup; stream or group may not exist yet
        }
    }

    @Override
    public void moveToDlq(String stream, String envelopeJson, DlqMetadata metadata) {
        connection.sync().xadd(
            RedisStreamNames.dlqFor(stream),
            XAddArgs.Builder.maxlen(100_000).approximateTrimming(),
            Map.of(
                "envelope", envelopeJson,
                "consumerGroup", metadata.consumerGroup(),
                "consumerName", metadata.consumerName(),
                "failureReason", metadata.failureReason(),
                "attempts", String.valueOf(metadata.attempts()),
                "deadLetteredAt", metadata.deadLetteredAt()
            )
        );
    }


    private record ConsumerInfo(String name, long idleMillis, long pending) {
        private static ConsumerInfo from(Object value) {
            if (value instanceof List<?> entries) {
                return fromEntries(entries);
            }
            if (value instanceof Map<?, ?> fields) {
                return new ConsumerInfo(
                    text(fields.get("name")),
                    number(fields.get("idle")),
                    number(fields.get("pending"))
                );
            }
            throw new IllegalStateException("unexpected Redis consumer info shape");
        }

        private static ConsumerInfo fromEntries(List<?> entries) {
            String name = null;
            long idleMillis = 0;
            long pending = 0;
            for (int index = 0; index + 1 < entries.size(); index += 2) {
                String key = text(entries.get(index));
                Object rawValue = entries.get(index + 1);
                if ("name".equals(key)) {
                    name = text(rawValue);
                } else if ("idle".equals(key)) {
                    idleMillis = number(rawValue);
                } else if ("pending".equals(key)) {
                    pending = number(rawValue);
                }
            }
            return new ConsumerInfo(Objects.requireNonNull(name, "consumer name is required"), idleMillis, pending);
        }

        private static String text(Object value) {
            return Objects.requireNonNull(value, "Redis consumer field is required").toString();
        }

        private static long number(Object value) {
            if (value instanceof Number number) {
                return number.longValue();
            }
            return Long.parseLong(text(value));
        }
    }

    private static StreamEntry toEntry(io.lettuce.core.StreamMessage<String, String> message) {
        return new StreamEntry(message.getId(), message.getBody().get("envelope"));
    }
}
