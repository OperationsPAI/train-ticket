package com.trainticket.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    public static ServiceProfile profile() {
        return new ServiceProfile(
            "payment",
            "Payment",
            "java",
            "phase-1-core",
            "WP-10",
            "PaymentIntent, Refund, CallbackRecord, IdempotencyKey"
        );
    }
}
