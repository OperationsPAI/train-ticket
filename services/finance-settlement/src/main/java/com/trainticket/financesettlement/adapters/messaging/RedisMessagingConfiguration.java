package com.trainticket.financesettlement.adapters.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.financesettlement.application.EventPublisher;
import com.trainticket.financesettlement.application.EventSubscriber;
import com.trainticket.financesettlement.application.FinanceSettlementEventHandler;
import com.trainticket.financesettlement.application.PublishFailedException;
import com.trainticket.financesettlement.application.SubscribeFailedException;
import com.trainticket.platformkit.messaging.RedisEventPublisher;
import com.trainticket.platformkit.messaging.RedisEventSubscriber;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "finance.messaging.redis.enabled", havingValue = "true", matchIfMissing = true)
public class RedisMessagingConfiguration {
    @Bean(destroyMethod = "close") RedisEventPublisher platformRedisEventPublisher(ObjectMapper objectMapper, RedisMessagingProperties properties) { return RedisEventPublisher.fromUrl(properties.url(), objectMapper); }
    @Bean EventPublisher redisEventPublisher(RedisEventPublisher publisher) { return envelope -> { try { publisher.publish(envelope); } catch (com.trainticket.platformkit.messaging.PublishFailedException e) { throw new PublishFailedException(e.getMessage(), e); } }; }
    @Bean(destroyMethod = "close") RedisEventSubscriber platformRedisEventSubscriber(ObjectMapper objectMapper, RedisMessagingProperties properties) { return RedisEventSubscriber.fromUrl(properties.url(), objectMapper); }
    @Bean EventSubscriber redisEventSubscriber(RedisEventSubscriber subscriber) { return (streams, group, consumerName, handler) -> { try { subscriber.subscribe(streams, group, consumerName, envelope -> switch (handler.handle(envelope)) { case SUCCESS -> com.trainticket.platformkit.messaging.HandlerResult.SUCCESS; case TRANSIENT_FAILURE -> com.trainticket.platformkit.messaging.HandlerResult.TRANSIENT_FAILURE; case FATAL_FAILURE -> com.trainticket.platformkit.messaging.HandlerResult.FATAL_FAILURE; }); } catch (com.trainticket.platformkit.messaging.SubscribeFailedException e) { throw new SubscribeFailedException(e.getMessage(), e); } }; }
    @Bean SmartLifecycle financeSubscriptionLifecycle(EventSubscriber subscriber, RedisMessagingProperties properties, FinanceSettlementEventHandler handler) { return new SmartLifecycle() { private boolean running; public void start(){ subscriber.subscribe(RedisMessagingProperties.SUBSCRIBED_STREAMS, RedisMessagingProperties.CONSUMER_GROUP, properties.consumerName(), handler); running=true;} public void stop(){running=false;} public boolean isRunning(){return running;} }; }
}
