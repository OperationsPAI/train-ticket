package com.trainticket.adminaudit.adapters.http;

import com.trainticket.platformkit.http.CanonicalErrorWriter;
import com.trainticket.platformkit.http.PlatformKitExceptionHandler;
import com.trainticket.platformkit.idempotency.IdempotencyStore;
import com.trainticket.platformkit.idempotency.InMemoryIdempotencyStore;
import com.trainticket.platformkit.idempotency.IdempotencyFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
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
    FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(IdempotencyStore store, CanonicalErrorWriter errorWriter) {
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(new IdempotencyFilter(store, errorWriter));
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    PlatformKitExceptionHandler platformKitExceptionHandler() {
        return new PlatformKitExceptionHandler();
    }
}
