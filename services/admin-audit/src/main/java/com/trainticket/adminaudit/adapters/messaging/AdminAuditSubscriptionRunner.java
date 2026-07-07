package com.trainticket.adminaudit.adapters.messaging;

import com.trainticket.adminaudit.application.AdminAuditInboundEventHandler;
import com.trainticket.adminaudit.application.ports.EventSubscriber;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

@Component
@ConditionalOnProperty(name = "ADMIN_AUDIT_REDIS_ENABLED", havingValue = "true", matchIfMissing = true)
public class AdminAuditSubscriptionRunner implements CommandLineRunner {
    public static final List<String> SUBSCRIBED_STREAMS = List.of("events:customer-service", "events:legacy-acl");
    static final String CONSUMER_GROUP = "admin-audit";

    private final EventSubscriber subscriber;
    private final ConsumedEventLog consumedEventLog;
    private final AdminAuditInboundEventHandler handler;
    private final Optional<PlatformTransactionManager> transactionManager;

    public AdminAuditSubscriptionRunner(
        EventSubscriber subscriber,
        ConsumedEventLog consumedEventLog,
        AdminAuditInboundEventHandler handler,
        Optional<PlatformTransactionManager> transactionManager
    ) {
        this.subscriber = subscriber;
        this.consumedEventLog = consumedEventLog;
        this.handler = handler;
        this.transactionManager = transactionManager;
    }

    @Override
    public void run(String... args) {
        subscriber.subscribe(
            SUBSCRIBED_STREAMS,
            CONSUMER_GROUP,
            CONSUMER_GROUP + "-" + UUID.randomUUID(),
            new DeduplicatingEventHandler(consumedEventLog, handler, transactionManager.orElse(null))
        );
    }
}
