package com.trainticket.platformkit;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.http.CanonicalErrorWriter;
import com.trainticket.platformkit.http.PlatformKitExceptionHandler;
import com.trainticket.platformkit.idempotency.IdempotencyFilter;
import com.trainticket.platformkit.idempotency.IdempotencyStore;
import com.trainticket.platformkit.idempotency.InMemoryIdempotencyStore;
import com.trainticket.platformkit.messaging.RedisEventPublisher;
import com.trainticket.platformkit.messaging.RedisEventSubscriber;
import com.trainticket.platformkit.observability.EventConsumerTracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PlatformKitConfiguration {
    @Bean
    @ConditionalOnMissingBean
    ObjectMapper objectMapper() {
        return new ObjectMapper()
            .findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Bean
    @ConditionalOnMissingBean
    CanonicalErrorWriter canonicalErrorWriter(ObjectMapper objectMapper) {
        return new CanonicalErrorWriter(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    PlatformKitExceptionHandler platformKitExceptionHandler() {
        return new PlatformKitExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    IdempotencyStore idempotencyStore() {
        return new InMemoryIdempotencyStore();
    }

    @Bean
    @ConditionalOnMissingBean
    FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(IdempotencyStore store, CanonicalErrorWriter errorWriter) {
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(new IdempotencyFilter(store, errorWriter));
        registration.setOrder(-50);
        return registration;
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "platform.java-kit.redis", name = "enabled", havingValue = "true")
    RedisEventPublisher redisEventPublisher(@Value("${REDIS_URL:redis://localhost:6379}") String redisUrl, ObjectMapper objectMapper) {
        return RedisEventPublisher.fromUrl(redisUrl, objectMapper);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "platform.java-kit.redis", name = "enabled", havingValue = "true")
    RedisEventSubscriber redisEventSubscriber(
        @Value("${REDIS_URL:redis://localhost:6379}") String redisUrl,
        ObjectMapper objectMapper,
        ObjectProvider<EventConsumerTracer> eventConsumerTracer
    ) {
        EventConsumerTracer tracer = eventConsumerTracer.getIfAvailable();
        return tracer == null
            ? RedisEventSubscriber.fromUrl(redisUrl, objectMapper)
            : RedisEventSubscriber.fromUrl(redisUrl, objectMapper, tracer);
    }
}
