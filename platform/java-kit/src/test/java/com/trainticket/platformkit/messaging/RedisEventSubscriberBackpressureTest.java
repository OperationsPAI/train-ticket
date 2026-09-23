package com.trainticket.platformkit.messaging;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lettuce.core.RedisClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class RedisEventSubscriberBackpressureTest {
    @Test
    @EnabledIfEnvironmentVariable(named="TEST_PAYMENT_FILTER_EVENTS", matches=".+")
    void blockedHandlersBoundReadsAndShutdownDrainsFetchedEntries() throws Exception {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var rows = mapper.readTree(Files.readString(Path.of(System.getenv("TEST_PAYMENT_FILTER_EVENTS"))));
        assertTrue(rows.size() >= 100);
        var redis = RedisClient.create(System.getenv("TEST_REDIS_URL"));
        String stream = "events:backpressure-test-" + UUID.randomUUID(), group = "backpressure-test";
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var started = new AtomicInteger();
        try (var connection = redis.connect(); var subscriber = RedisEventSubscriber.fromUrl(System.getenv("TEST_REDIS_URL"),mapper);
             var closer = Executors.newSingleThreadExecutor()) {
            var operations = new LettuceRedisStreamOperations(connection);
            operations.createGroup(stream,group);
            for (int index=0;index<2000;index++) operations.publish(stream,mapper.writeValueAsString(rows.get(index%100)));
            subscriber.subscribe(List.of(stream),group,"current-pod",event -> {
                started.incrementAndGet();
                entered.countDown();
                try { if (!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("handler release timed out"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                return HandlerResult.SUCCESS;
            });
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS));
                Thread.sleep(300);
                assertEquals(2,started.get());
                assertTrue(connection.sync().xpending(stream,group).getCount() <= 100);
                var closed = closer.submit(subscriber::close);
                assertThrows(TimeoutException.class, () -> closed.get(300,TimeUnit.MILLISECONDS));
                release.countDown();
                closed.get(10,TimeUnit.SECONDS);
                assertEquals(100,started.get());
                assertEquals(0,connection.sync().xpending(stream,group).getCount());
            } finally { release.countDown(); }
            connection.sync().del(stream);
        } finally { redis.shutdown(); }
    }
}
