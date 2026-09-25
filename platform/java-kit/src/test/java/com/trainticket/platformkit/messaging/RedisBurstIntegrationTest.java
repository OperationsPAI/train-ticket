package com.trainticket.platformkit.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lettuce.core.Consumer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.XClaimArgs;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "TEST_REDIS_URL", matches = ".+")
class RedisBurstIntegrationTest {
    @Test
    void autoClaimReachesTheTailOfAPendingListLargerThanTenPages() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        RedisClient redis = RedisClient.create(System.getenv("TEST_REDIS_URL"));
        String producer = "autoclaim-cursor-test-" + UUID.randomUUID();
        String stream = "events:" + producer;
        String group = "autoclaim-cursor-test";
        int count = 1_205;
        List<String> eventIds = new ArrayList<>(count);
        List<String> pendingIds = new ArrayList<>(count);
        var observed = ConcurrentHashMap.<String>newKeySet();
        try (var connection = redis.connect()) {
            var operations = new LettuceRedisStreamOperations(connection);
            operations.createGroup(stream, group);
            for (int index = 0; index < count; index++) {
                var event = new EventEnvelopeFactory(producer)
                    .create("CursorProbe", Map.of("sequence", index));
                eventIds.add(event.eventId());
                operations.publish(stream, mapper.writeValueAsString(event));
            }

            while (pendingIds.size() < count) {
                pendingIds.addAll(operations.readGroup(stream, group, "previous-pod").stream()
                    .map(RedisStreamOperations.StreamEntry::id)
                    .toList());
            }
            assertThat(connection.sync().xpending(stream, group).getCount()).isEqualTo(count);

            XClaimArgs stale = new XClaimArgs()
                .minIdleTime(Duration.ZERO)
                .idle(Duration.ofMillis(LettuceRedisStreamOperations.configuredAutoClaimMinIdleMillis() + 1_000L));
            for (int offset = 0; offset < pendingIds.size(); offset += 100) {
                List<String> batch = pendingIds.subList(offset, Math.min(offset + 100, pendingIds.size()));
                connection.sync().xclaim(
                    stream,
                    Consumer.from(group, "previous-pod"),
                    stale,
                    batch.toArray(String[]::new)
                );
            }

            RedisEventSubscriber subscriber = new RedisEventSubscriber(operations, mapper, null);
            try {
                for (int page = 0; page < 13; page++) {
                    subscriber.recoverOnce(stream, group, "current-pod", event -> {
                        observed.add(event.eventId());
                        return HandlerResult.SUCCESS;
                    });
                }
            } finally {
                subscriber.close();
            }

            assertThat(observed).hasSize(count).contains(eventIds.getLast());
            assertThat(connection.sync().xpending(stream, group).getCount()).isZero();
        } finally {
            try (var cleanup = redis.connect()) {
                cleanup.sync().del(stream);
            }
            redis.shutdown();
        }
    }

    @Test
    void retainsPendingEntriesWhenPruningAnIdleConsumer() throws Exception {
        RedisClient redis = RedisClient.create(System.getenv("TEST_REDIS_URL"));
        String stream = "events:prune-test-" + UUID.randomUUID();
        String group = "prune-test";
        try (var connection = redis.connect()) {
            var operations = new LettuceRedisStreamOperations(connection);
            operations.createGroup(stream, group);
            operations.publish(stream, "{}");
            assertThat(operations.readGroup(stream, group, "previous-pod")).hasSize(1);
            Thread.sleep(10);
            operations.pruneDeadConsumers(stream, group, "current-pod", 0);
            assertThat(connection.sync().xpending(stream, group).getCount()).isEqualTo(1);
            connection.sync().del(stream);
        } finally {
            redis.shutdown();
        }
    }

    @Test
    void handlesAnEntireBurstWithoutWaitingForPendingRecovery() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        RedisClient redis = RedisClient.create(System.getenv("TEST_REDIS_URL"));
        String producer = "backpressure-test-" + UUID.randomUUID();
        String stream = "events:" + producer;
        String group = "booking-test";
        int count = 100;
        CountDownLatch handled = new CountDownLatch(count);
        var observed = ConcurrentHashMap.<String>newKeySet();
        try (var connection = redis.connect();
             var subscriber = RedisEventSubscriber.fromUrl(System.getenv("TEST_REDIS_URL"), mapper)) {
            var operations = new LettuceRedisStreamOperations(connection);
            operations.createGroup(stream, group);
            for (int i = 0; i < count; i++) {
                var event = new EventEnvelopeFactory(producer)
                    .create("JourneyOrderCreated", Map.of("orderId", UUID.randomUUID().toString()));
                operations.publish(stream, mapper.writeValueAsString(event));
            }
            subscriber.subscribe(List.of(stream), group, "consumer", event -> {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                observed.add(event.eventId());
                handled.countDown();
                return HandlerResult.SUCCESS;
            });
            assertThat(handled.await(15, TimeUnit.SECONDS)).isTrue();
            assertThat(observed).hasSize(count);
            subscriber.close();
            assertThat(connection.sync().xpending(stream, group).getCount()).isZero();
            connection.sync().del(stream);
        } finally {
            redis.shutdown();
        }
    }
}
