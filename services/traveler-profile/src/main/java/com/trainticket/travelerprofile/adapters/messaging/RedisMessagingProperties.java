package com.trainticket.travelerprofile.adapters.messaging;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RedisMessagingProperties {
    static final String CONSUMER_GROUP = "traveler-profile";
    static final List<String> SUBSCRIBED_STREAMS = List.of("events:identity-verification");

    private final String consumerName;

    public RedisMessagingProperties(@Value("${HOSTNAME:local}") String instanceId) {
        this.consumerName = CONSUMER_GROUP + "-" + instanceId;
    }

    public String consumerName() {
        return consumerName;
    }
}
