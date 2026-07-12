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
            "phase-1-domain-foundation",
            "REQ-010-Journey-Order-domain-foundation",
            "JourneyOrder aggregate, OrderItem, TravelerRef, MonetarySummary, OrderTimeline facts, confirmation condition guards"
        );
    }
}
