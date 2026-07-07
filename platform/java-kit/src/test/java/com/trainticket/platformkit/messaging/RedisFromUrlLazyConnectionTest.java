package com.trainticket.platformkit.messaging;

import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class RedisFromUrlLazyConnectionTest {
    @Test
    void publisherFromUrlDoesNotConnectDuringConstruction() throws Exception {
        try (RedisEventPublisher publisher = RedisEventPublisher.fromUrl("redis://127.0.0.1:1", new ObjectMapper())) {
            assertThatCode(() -> { }).doesNotThrowAnyException();
        }
    }

    @Test
    void subscriberFromUrlDoesNotConnectDuringConstruction() {
        RedisEventSubscriber subscriber = RedisEventSubscriber.fromUrl("redis://127.0.0.1:1", new ObjectMapper());
        assertThatCode(() -> { }).doesNotThrowAnyException();
        subscriber.close();
    }
}
