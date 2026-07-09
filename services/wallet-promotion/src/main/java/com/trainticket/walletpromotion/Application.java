package com.trainticket.walletpromotion;

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
            "wallet-promotion",
            "Wallet / Promotion",
            "java",
            "activation-wave-2",
            "REQ-112 wallet-promotion service",
            "PromotionInstrument, WalletAccount, BenefitRedemption"
        );
    }
}
