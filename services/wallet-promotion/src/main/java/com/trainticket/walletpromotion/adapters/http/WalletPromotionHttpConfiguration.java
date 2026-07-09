package com.trainticket.walletpromotion.adapters.http;

import com.trainticket.platformkit.http.PlatformKitExceptionHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WalletPromotionHttpConfiguration {
    @Bean
    @ConditionalOnMissingBean
    PlatformKitExceptionHandler platformKitExceptionHandler() {
        return new PlatformKitExceptionHandler();
    }
}
