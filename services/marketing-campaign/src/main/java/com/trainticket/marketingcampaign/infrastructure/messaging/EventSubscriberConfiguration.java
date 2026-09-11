package com.trainticket.marketingcampaign.infrastructure.messaging;

import com.trainticket.platformkit.messaging.RedisEventSubscriber;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
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
            JourneyOrderEventHandler eventHandler,
            // Stable per-pod consumer name, not a per-boot UUID: a restarted
            // process must reclaim its own pending entries rather than orphan
            // them in a consumer name that will never appear again.
            @Value("${HOSTNAME:local}") String instanceId) {
        return () -> eventSubscriber.subscribe(
            MarketingCampaignSubscriptions.streams(),
            MarketingCampaignSubscriptions.group(),
            "marketing-campaign-" + instanceId,
            eventHandler::handle
        );
    }
}
