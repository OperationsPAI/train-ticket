package com.trainticket.postsales;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    public static ServiceProfile profile() {
        return new ServiceProfile(
            "post-sales",
            "Post Sales",
            "java",
            "phase-1-core",
            "WP-14",
            "PostSalesCase, RefundDecision, ChangeExecutionPlan"
        );
    }
}
