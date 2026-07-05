package com.trainticket.platformkit.messaging;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class PublishAfterCommit {
    private PublishAfterCommit() {
    }

    public static void publish(EventPublisher publisher, EventEnvelope envelope) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publisher.publish(envelope);
                }
            });
        } else {
            publisher.publish(envelope);
        }
    }
}
