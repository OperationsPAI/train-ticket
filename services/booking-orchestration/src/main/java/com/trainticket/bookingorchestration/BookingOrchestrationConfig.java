package com.trainticket.bookingorchestration;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BookingOrchestrationConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

}
