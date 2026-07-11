package com.trainticket.marketingcampaign.infrastructure.messaging;

import com.trainticket.platformkit.messaging.RedisEventSubscriber;
import java.util.UUID;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EventSubscriberConfiguration {

    @Bean
    public JourneyOrderEventHandler journeyOrderEventHandler() {
        return new JourneyOrderEventHandler();
    }

    @Bean
    @ConditionalOnBean(RedisEventSubscriber.class)
    public SmartInitializingSingleton marketingCampaignSubscriptionStartup(
            RedisEventSubscriber eventSubscriber,
            JourneyOrderEventHandler eventHandler) {
        return () -> eventSubscriber.subscribe(
            MarketingCampaignSubscriptions.streams(),
            MarketingCampaignSubscriptions.group(),
            "marketing-campaign-" + UUID.randomUUID(),
            eventHandler::handle
        );
    }
}
