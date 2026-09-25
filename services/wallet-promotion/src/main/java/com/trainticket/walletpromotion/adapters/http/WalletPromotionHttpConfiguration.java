package com.trainticket.walletpromotion.adapters.http;

import com.trainticket.platformkit.http.PlatformKitExceptionHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class WalletPromotionHttpConfiguration {
    @Bean
    @ConditionalOnMissingBean
    PlatformKitExceptionHandler platformKitExceptionHandler() {
        return new PlatformKitExceptionHandler();
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    RestClient.Builder walletPromotionRestClientBuilder() {
        return RestClient.builder();
    }
}
