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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class RedisShutdownIntegrationTest {
    @Test
    @EnabledIfEnvironmentVariable(named="TEST_PAYMENT_FILTER_EVENTS", matches=".+")
    void shutdownFinishesTheFetchedBatchBeforeClosingHandlers() throws Exception {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var rows = mapper.readTree(Files.readString(Path.of(System.getenv("TEST_PAYMENT_FILTER_EVENTS"))));
        assertTrue(rows.size() >= 100);
        var redis = RedisClient.create(System.getenv("TEST_REDIS_URL"));
        String stream = "events:shutdown-test-" + UUID.randomUUID(), group = "shutdown-test";
        var entered = new CountDownLatch(1);
        var handled = new AtomicInteger();
        try (var connection = redis.connect(); var subscriber = RedisEventSubscriber.fromUrl(System.getenv("TEST_REDIS_URL"),mapper)) {
            var operations = new LettuceRedisStreamOperations(connection);
            operations.createGroup(stream,group);
            for (int index=0;index<100;index++) operations.publish(stream,mapper.writeValueAsString(rows.get(index)));
            subscriber.subscribe(List.of(stream),group,"current-pod",event -> {
                entered.countDown();
                try { Thread.sleep(10); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                handled.incrementAndGet();
                return HandlerResult.SUCCESS;
            });
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            subscriber.close();
            assertEquals(100,handled.get());
            assertEquals(0,connection.sync().xpending(stream,group).getCount());
            connection.sync().del(stream);
        } finally { redis.shutdown(); }
    }
}
