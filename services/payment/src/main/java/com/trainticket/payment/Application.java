package com.trainticket.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    public static ServiceProfile profile() {
        return new ServiceProfile(
            "payment",
            "Payment",
            "java",
            "domain-enrichment-payment-multichannel",
            "REQ-307-payment-multichannel-routing-timeout-partial-refund",
            "PaymentIntent multi-channel routing, channel-specific timeout, outbox timeout events, original-route partial refund lifecycle"
        );
    }
}
