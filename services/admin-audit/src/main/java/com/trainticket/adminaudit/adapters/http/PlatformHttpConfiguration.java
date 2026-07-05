package com.trainticket.adminaudit.adapters.http;

import com.trainticket.platformkit.http.PlatformKitExceptionHandler;
import com.trainticket.platformkit.idempotency.IdempotencyStore;
import com.trainticket.platformkit.idempotency.InMemoryIdempotencyStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PlatformHttpConfiguration {
    @Bean
    @ConditionalOnMissingBean
    IdempotencyStore idempotencyStore() {
        return new InMemoryIdempotencyStore();
    }

    @Bean
    @ConditionalOnMissingBean
    PlatformKitExceptionHandler platformKitExceptionHandler() {
        return new PlatformKitExceptionHandler();
    }
}
