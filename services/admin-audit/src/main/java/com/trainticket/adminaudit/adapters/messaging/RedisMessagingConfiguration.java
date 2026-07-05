package com.trainticket.adminaudit.adapters.messaging;

import com.trainticket.adminaudit.application.ports.EventEnvelope;
import com.trainticket.adminaudit.application.ports.EventSubscriber;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(RedisEventSubscriber.class)
public class RedisMessagingConfiguration implements ApplicationRunner {
    private final RedisEventSubscriber subscriber;
    private final ConsumedEventLog consumedEventLog;

    public RedisMessagingConfiguration(RedisEventSubscriber subscriber, ConsumedEventLog consumedEventLog) {
        this.subscriber = subscriber;
        this.consumedEventLog = consumedEventLog;
    }

    @Override
    public void run(ApplicationArguments args) {
        // The contract currently lists no business streams consumed by admin-audit.
        // Keep the adapter wired and ready; an empty subscription set requires no Redis group.
        List<String> streams = List.of();
        if (!streams.isEmpty()) {
            EventSubscriber.EventHandler handler = new DeduplicatingEventHandler(consumedEventLog, this::handle);
            subscriber.subscribe(streams, "admin-audit", "admin-audit-" + UUID.randomUUID(), handler);
        }
    }

    private EventSubscriber.HandlerResult handle(EventEnvelope envelope) {
        return EventSubscriber.HandlerResult.SUCCESS;
    }
}
