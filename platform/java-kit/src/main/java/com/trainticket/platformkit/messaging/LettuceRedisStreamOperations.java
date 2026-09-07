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
    static final String STREAM_MAXLEN_ENV = "EVENT_STREAM_MAXLEN";
    static final long DEFAULT_STREAM_MAXLEN = 10_000L;
    static final String AUTOCLAIM_MIN_IDLE_ENV = "AUTOCLAIM_MIN_IDLE_MS";
    /**
     * How long a message must sit pending before another consumer may reclaim it. Every reclaim
     * increments Redis' redelivery counter, so this is also the tick rate of that counter: it must
     * be long enough that an ordinary backlog cannot masquerade as repeated failure, and it should
     * comfortably exceed the slowest expected handler. 5 minutes matches the idle window already
     * used to declare a consumer dead.
     */
    static final long DEFAULT_AUTOCLAIM_MIN_IDLE_MS = 5 * 60 * 1_000L;

    private static final long BLOCK_MS = Long.parseLong(System.getenv().getOrDefault("CONSUMER_BLOCK_MS", "100"));
    private static final int BATCH_COUNT = Integer.parseInt(System.getenv().getOrDefault("CONSUMER_BATCH_COUNT", "100"));
    /**
     * Cap on entries kept per stream. Redis streams are never read destructively, so without a cap
     * every published event stays resident forever and eventually exhausts the Redis memory limit.
     */
    private static final long STREAM_MAXLEN = streamMaxLen(System.getenv(STREAM_MAXLEN_ENV));
    private static final long AUTOCLAIM_MIN_IDLE_MS = autoClaimMinIdleMillis(System.getenv(AUTOCLAIM_MIN_IDLE_ENV));

    private final StatefulRedisConnection<String, String> connection;

    public LettuceRedisStreamOperations(StatefulRedisConnection<String, String> connection) {
        this.connection = Objects.requireNonNull(connection, "connection is required");
    }

    /**
     * {@code MAXLEN ~ n} — approximate trimming keeps XADD O(1) by letting Redis drop whole
     * radix-tree nodes instead of walking to an exact length.
     */
    static XAddArgs cappedStreamArgs() {
        return XAddArgs.Builder.maxlen(STREAM_MAXLEN).approximateTrimming();
    }

    static long configuredStreamMaxLen() {
        return STREAM_MAXLEN;
    }

    static long configuredAutoClaimMinIdleMillis() {
        return AUTOCLAIM_MIN_IDLE_MS;
    }

    static long autoClaimMinIdleMillis(String configured) {
        if (configured == null || configured.isBlank()) {
            return DEFAULT_AUTOCLAIM_MIN_IDLE_MS;
        }
        try {
            long millis = Long.parseLong(configured.trim());
            if (millis > 0) {
                return millis;
            }
        } catch (NumberFormatException ignored) {
            // fall through to the warning below
        }
        LoggerFactory.getLogger(LettuceRedisStreamOperations.class)
            .warn("{}={} is not a positive integer; using default {}", AUTOCLAIM_MIN_IDLE_ENV, configured, DEFAULT_AUTOCLAIM_MIN_IDLE_MS);
        return DEFAULT_AUTOCLAIM_MIN_IDLE_MS;
    }

    static long streamMaxLen(String configured) {
        if (configured == null || configured.isBlank()) {
            return DEFAULT_STREAM_MAXLEN;
        }
        try {
            long maxlen = Long.parseLong(configured.trim());
            if (maxlen > 0) {
                return maxlen;
            }
        } catch (NumberFormatException ignored) {
            // fall through to the warning below
        }
        LoggerFactory.getLogger(LettuceRedisStreamOperations.class)
            .warn("{}={} is not a positive integer; using default {}", STREAM_MAXLEN_ENV, configured, DEFAULT_STREAM_MAXLEN);
        return DEFAULT_STREAM_MAXLEN;
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
        return connection.sync().xadd(stream, cappedStreamArgs(), Map.of("envelope", envelopeJson));
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
                    cappedStreamArgs(),
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
            .xautoclaim(stream, XAutoClaimArgs.Builder.xautoclaim(Consumer.from(group, consumerName), Duration.ofMillis(AUTOCLAIM_MIN_IDLE_MS), "0-0").count(100))
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
            cappedStreamArgs(),
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
