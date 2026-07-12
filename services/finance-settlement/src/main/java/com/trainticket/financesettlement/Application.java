package com.trainticket.financesettlement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    public static ServiceProfile profile() {
        return new ServiceProfile(
            "finance-settlement",
            "Finance Settlement",
            "java",
            "phase-1-limited",
            "REQ-024",
            "RevenueRecognition, ReconciliationCase, SettlementView, ConsumedEventLog"
        );
    }
}
