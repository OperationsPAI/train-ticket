package com.trainticket.bookingorchestration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    public static ServiceProfile profile() {
        return new ServiceProfile(
            "booking-orchestration",
            "Booking Orchestration",
            "java",
            "phase-1-core",
            "REQ-011",
            "BookingSaga progress, SegmentBooking lifecycle, normalized ProviderReservation mapping"
        );
    }
}
