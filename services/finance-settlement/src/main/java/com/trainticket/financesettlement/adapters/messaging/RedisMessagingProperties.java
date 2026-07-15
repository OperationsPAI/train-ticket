package com.trainticket.financesettlement.adapters.messaging;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RedisMessagingProperties {
    static final String CONSUMER_GROUP = "finance-settlement";
    static final List<String> SUBSCRIBED_STREAMS = List.of(
        "events:payment",
        "events:provider-integration",
        "events:booking-orchestration",
        "events:post-sales",
        "events:wallet-promotion",
        "events:payment-channel",
        "events:ancillary-service"
    );

    private final String url;
    private final String consumerName;

    public RedisMessagingProperties(
        @Value("${REDIS_URL:${redis.url:redis://localhost:6379}}") String url,
        @Value("${HOSTNAME:local}") String instanceId
    ) {
        this.url = url;
        this.consumerName = CONSUMER_GROUP + "-" + instanceId;
    }

    public String url() {
        return url;
    }

    public String consumerName() {
        return consumerName;
    }
}
