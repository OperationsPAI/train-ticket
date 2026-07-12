package com.trainticket.adminaudit.adapters.messaging;

import com.trainticket.adminaudit.application.ports.EventSubscriber;
import com.trainticket.platformkit.messaging.EventEnvelope;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public class DeduplicatingEventHandler implements EventSubscriber.EventHandler {
    private final ConsumedEventLog consumedEventLog;
    private final EventSubscriber.EventHandler delegate;
    private final TransactionTemplate transactionTemplate;

    public DeduplicatingEventHandler(ConsumedEventLog consumedEventLog, EventSubscriber.EventHandler delegate) {
        this(consumedEventLog, delegate, null);
    }

    public DeduplicatingEventHandler(
        ConsumedEventLog consumedEventLog,
        EventSubscriber.EventHandler delegate,
        PlatformTransactionManager transactionManager
    ) {
        this.consumedEventLog = consumedEventLog;
        this.delegate = delegate;
        this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    @Override
    public EventSubscriber.HandlerResult handle(EventEnvelope envelope) {
        if (transactionTemplate == null) {
            return handleInCurrentThread(envelope, false);
        }
        return transactionTemplate.execute(status -> {
            EventSubscriber.HandlerResult result = handleInCurrentThread(envelope, true);
            if (result == EventSubscriber.HandlerResult.TRANSIENT_FAILURE) {
                status.setRollbackOnly();
            }
            return result;
        });
    }

    private EventSubscriber.HandlerResult handleInCurrentThread(EventEnvelope envelope, boolean transactional) {
        if (!consumedEventLog.recordIfNew(envelope.eventId())) {
            return EventSubscriber.HandlerResult.SUCCESS;
        }
        EventSubscriber.HandlerResult result = delegate.handle(envelope);
        if (!transactional && result == EventSubscriber.HandlerResult.TRANSIENT_FAILURE) {
            consumedEventLog.discard(envelope.eventId());
        }
        return result;
    }
}
