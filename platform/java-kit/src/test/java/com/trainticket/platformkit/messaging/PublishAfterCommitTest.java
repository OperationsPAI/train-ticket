package com.trainticket.platformkit.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PublishAfterCommitTest {
    @Test
    void publishesOnlyAfterCommitWhenTransactionSynchronizationIsActive() {
        EventEnvelope envelope = new EventEnvelopeFactory("payment").create("PaymentCaptured", Map.of());
        List<EventEnvelope> published = new ArrayList<>();
        TransactionSynchronizationManager.initSynchronization();
        try {
            PublishAfterCommit.publish(published::add, envelope);
            assertThat(published).isEmpty();
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            assertThat(published).containsExactly(envelope);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void doesNotSwallowPublishErrors() {
        EventEnvelope envelope = new EventEnvelopeFactory("payment").create("PaymentCaptured", Map.of());
        assertThatThrownBy(() -> PublishAfterCommit.publish(event -> {
            throw new PublishFailedException("boom", null);
        }, envelope)).isInstanceOf(PublishFailedException.class);
    }
}
