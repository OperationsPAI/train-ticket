package com.trainticket.bookingorchestration.application;

import com.trainticket.platformkit.messaging.EventEnvelope;
import java.util.List;
import java.util.function.Function;

public record SubscriberConfig(
    List<String> streams,
    String group,
    String consumerName,
    Function<EventEnvelope, HandlerResult> handler
) {
    public SubscriberConfig {
        if (streams == null || streams.isEmpty()) {
            throw new IllegalArgumentException("streams must not be empty");
        }
        if (group == null || group.isBlank()) {
            throw new IllegalArgumentException("group is required");
        }
        if (consumerName == null || consumerName.isBlank()) {
            throw new IllegalArgumentException("consumerName is required");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler is required");
        }
    }
}
