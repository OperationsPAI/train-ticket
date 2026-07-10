package com.trainticket.groupbooking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    public static ServiceProfile profile() {
        return new ServiceProfile(
            "group-booking",
            "Group Booking",
            "java",
            "phase-1-commercial",
            "REQ-214",
            "GroupBooking, GroupMember, GroupFare"
        );
    }
}
