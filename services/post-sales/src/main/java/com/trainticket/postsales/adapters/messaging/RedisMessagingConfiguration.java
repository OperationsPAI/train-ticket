package com.trainticket.postsales.adapters.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.messaging.LazyRedisEventPublisher;
import com.trainticket.platformkit.messaging.LazyRedisEventSubscriber;
import com.trainticket.platformkit.messaging.RedisEventSubscriber;
import com.trainticket.platformkit.messaging.RedisPingReadinessProbe;
import com.trainticket.postsales.application.EventPublisher;
import com.trainticket.postsales.application.EventSubscriber;
import com.trainticket.postsales.application.PostSalesEventHandler;
import com.trainticket.postsales.application.PublishFailedException;
import com.trainticket.postsales.application.SubscribeFailedException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@ConditionalOnProperty(name = "post-sales.messaging.redis.enabled", havingValue = "true", matchIfMissing = true)
public class RedisMessagingConfiguration {
    @Bean(destroyMethod = "close")
    LazyRedisEventPublisher platformRedisEventPublisher(ObjectMapper objectMapper, RedisMessagingProperties properties) {
        return new LazyRedisEventPublisher(properties.redisUrl(), objectMapper);
    }

    @Bean
    EventPublisher redisEventPublisher(LazyRedisEventPublisher publisher) {
        return envelope -> {
            try {
                publisher.publish(envelope);
            } catch (com.trainticket.platformkit.messaging.PublishFailedException exception) {
                throw new PublishFailedException(exception.getMessage(), exception);
            }
        };
    }

    @Bean(destroyMethod = "close")
    LazyRedisEventSubscriber platformRedisEventSubscriber(ObjectMapper objectMapper, RedisMessagingProperties properties) {
        return new LazyRedisEventSubscriber(properties.redisUrl(), objectMapper);
    }

    @Bean(destroyMethod = "close")
    RedisEventSubscriber capacityRedisEventSubscriber(ObjectMapper objectMapper, RedisMessagingProperties properties) {
        return RedisEventSubscriber.fromUrl(properties.redisUrl(), objectMapper, capacityConsumerThreads());
    }

    /**
     * The capacity stream gets its own subscriber because it carries a combined
     * availability snapshot on every admission and would otherwise sit in the
     * shared handler queue behind everything else.
     *
     * Its thread count was fixed at four, and that is the number that decides a
     * refund's last hop: the case is applied by `CapacityReleased`, which arrives
     * on this stream. Measured on 2026-09-27 at the eight-times burst, a case
     * created at `22:28:00.35` had both of its holds released at `22:28:03.97`
     * and reached `APPLIED` at `22:28:10.29` -- 6.3 seconds of the ten second
     * poll spent waiting for that one event, with this group 15.8 seconds behind
     * on `events:capacity-availability`. It is configurable so the number can
     * move with the burst rather than living in the code.
     */
    private static int capacityConsumerThreads() {
        String configured = System.getenv("CAPACITY_CONSUMER_THREADS");
        if (configured == null || configured.isBlank()) {
            return 4;
        }
        try {
            int threads = Integer.parseInt(configured.trim());
            return threads > 0 ? threads : 4;
        } catch (NumberFormatException ignored) {
            return 4;
        }
    }

    @Bean
    @Primary
    EventSubscriber redisEventSubscriber(LazyRedisEventSubscriber subscriber, RedisEventSubscriber capacityRedisEventSubscriber) {
        return (streams, group, consumerName, handler) -> {
            try {
                com.trainticket.platformkit.messaging.EventHandler dispatch = envelope -> switch (handler.handle(envelope)) {
                    case SUCCESS -> com.trainticket.platformkit.messaging.HandlerResult.SUCCESS;
                    case TRANSIENT_FAILURE -> com.trainticket.platformkit.messaging.HandlerResult.TRANSIENT_FAILURE;
                    case FATAL_FAILURE -> com.trainticket.platformkit.messaging.HandlerResult.FATAL_FAILURE;
                };
                var capacity = streams.stream()
                    .filter(stream -> stream.equals(RedisStreamNames.forProducer("capacity-availability"))).toList();
                var other = streams.stream().filter(stream -> !capacity.contains(stream)).toList();
                if (!capacity.isEmpty()) {
                    capacityRedisEventSubscriber.subscribe(capacity, group, consumerName, dispatch);
                }
                if (!other.isEmpty()) {
                    subscriber.subscribe(other, group, consumerName, dispatch);
                }
            } catch (com.trainticket.platformkit.messaging.SubscribeFailedException exception) {
                throw new SubscribeFailedException(exception.getMessage(), exception);
            }
        };
    }

    @Bean
    RedisSubscriptionLifecycle redisSubscriptionLifecycle(
        EventSubscriber subscriber,
        RedisMessagingProperties properties,
        PostSalesEventHandler handler
    ) {
        return new RedisSubscriptionLifecycle(subscriber, properties, handler);
    }

    @Bean
    RedisMessagingReadiness redisMessagingReadiness(RedisMessagingProperties properties) {
        return new RedisMessagingReadiness(new RedisPingReadinessProbe(properties.redisUrl()));
    }

    public record RedisMessagingReadiness(RedisPingReadinessProbe probe) {
        public boolean isReady() {
            return probe.isReady();
        }
    }
}
