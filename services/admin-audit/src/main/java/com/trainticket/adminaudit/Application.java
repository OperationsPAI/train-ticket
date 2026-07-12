package com.trainticket.adminaudit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    public static ServiceProfile profile() {
        return new ServiceProfile(
            "admin-audit",
            "Admin & Audit",
            "java",
            "phase-1-support",
            "WP-19",
            "OperatorIdentity, Approval, ManualAction, AuditTrail"
        );
    }
}
