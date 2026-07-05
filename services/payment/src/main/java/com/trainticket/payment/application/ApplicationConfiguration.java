package com.trainticket.payment.application;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApplicationConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
