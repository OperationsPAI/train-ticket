package com.trainticket.payment.application;

import com.trainticket.payment.domain.ChannelRouter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PaymentChannelSettings.class)
public class PaymentChannelConfiguration {

    /**
     * The router the command service routes with. Its table comes from
     * {@code application.properties}, which the release layers environment
     * overrides on top of.
     */
    @Bean
    ChannelRouter paymentChannelRouter(PaymentChannelSettings settings) {
        return settings.toRouter();
    }
}
