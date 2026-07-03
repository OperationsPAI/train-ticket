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
            "phase-1-domain-foundation",
            "REQ-012-Payment-domain-foundation",
            "PaymentIntent aggregate, Refund aggregate, ChannelCallbackRecord idempotency, LatePaymentCase facts, original-route refund lifecycle"
        );
    }
}
