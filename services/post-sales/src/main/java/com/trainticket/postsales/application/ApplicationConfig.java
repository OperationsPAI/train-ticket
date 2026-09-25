package com.trainticket.postsales.application;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

@Configuration
public class ApplicationConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnExpression("'${DATABASE_URL:}' == ''")
    CompensationMismatchStore compensationMismatchStore() {
        return new InMemoryCompensationMismatchStore();
    }
}
