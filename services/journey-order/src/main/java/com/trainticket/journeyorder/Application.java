package com.trainticket.journeyorder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    public static ServiceProfile profile() {
        return new ServiceProfile(
            "journey-order",
            "Journey Order",
            "java",
            "phase-1-core",
            "WP-08",
            "JourneyOrder, OrderItem, MonetarySummary, OrderTimeline"
        );
    }
}
