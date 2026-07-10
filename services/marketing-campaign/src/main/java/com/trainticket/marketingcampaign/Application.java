package com.trainticket.marketingcampaign;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    public static ServiceProfile profile() {
        return new ServiceProfile(
            "marketing-campaign",
            "Marketing Campaign",
            "java",
            "phase-1-commercial",
            "REQ-221",
            "Campaign, CampaignBudget, CouponTemplate, IssuanceBatch"
        );
    }
}
