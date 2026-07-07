package com.trainticket.platformkit.persistence;

import com.trainticket.platformkit.messaging.LettuceRedisStreamOperations;
import com.trainticket.platformkit.messaging.RedisStreamOperations;
import com.trainticket.platformkit.messaging.DlqMetadata;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.context.SmartLifecycle;

public class LazyRedisOutboxRelayLifecycle implements SmartLifecycle, AutoCloseable {
    private final DataSource dataSource;
    private final String redisUrl;
    private final RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private OutboxRelay relay;
    private volatile boolean dependencyReady = true;
    private volatile boolean running;

    public LazyRedisOutboxRelayLifecycle(DataSource dataSource, String redisUrl) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource is required");
        this.redisUrl = redisUrl == null || redisUrl.isBlank() ? "redis://localhost:6379" : redisUrl;
        this.client = RedisClient.create(this.redisUrl);
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        relay = new OutboxRelay(dataSource, new LazyRedisStreamOperations());
        relay.start();
        running = true;
    }

    private synchronized LettuceRedisStreamOperations operations() {
        try {
            if (connection == null || !connection.isOpen()) {
                connection = client.connect();
            }
            dependencyReady = true;
            return new LettuceRedisStreamOperations(connection);
        } catch (RuntimeException exception) {
            dependencyReady = false;
            throw exception;
        }
    }

    public boolean isReady() {
        return dependencyReady && (relay == null || relay.isDependencyReady());
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public synchronized void stop() {
        close();
    }

    @Override
    public synchronized void close() {
        running = false;
        if (relay != null) {
            relay.close();
        }
        if (connection != null) {
            connection.close();
        }
        client.shutdown();
        relay = null;
        connection = null;
        dependencyReady = true;
    }

    private final class LazyRedisStreamOperations implements RedisStreamOperations {
        @Override
        public void createGroup(String stream, String group) {
            operations().createGroup(stream, group);
        }

        @Override
        public String publish(String stream, String envelopeJson) {
            return operations().publish(stream, envelopeJson);
        }

        @Override
        public List<RedisStreamOperations.StreamEntry> readGroup(String stream, String group, String consumerName) {
            return operations().readGroup(stream, group, consumerName);
        }

        @Override
        public List<RedisStreamOperations.StreamEntry> autoClaim(String stream, String group, String consumerName) {
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
    }
}
