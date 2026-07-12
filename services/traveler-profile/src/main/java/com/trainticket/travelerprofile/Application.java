package com.trainticket.travelerprofile;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    public static ServiceProfile profile() {
        return new ServiceProfile(
            "traveler-profile",
            "Traveler Profile",
            "java",
            "phase-1-support",
            "WP-16",
            "TravelerProfile, Document, EligibilitySummary, PreferenceSnapshot"
        );
    }
}
