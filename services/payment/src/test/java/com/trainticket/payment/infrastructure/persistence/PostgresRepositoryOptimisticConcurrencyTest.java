package com.trainticket.payment.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trainticket.payment.domain.Money;
import com.trainticket.payment.domain.PaymentIntent;
import com.trainticket.payment.domain.Refund;
import com.trainticket.platformkit.persistence.OptimisticConcurrencyException;
import com.trainticket.platformkit.persistence.Snapshot;
import com.trainticket.platformkit.persistence.SnapshotRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;

class PostgresRepositoryOptimisticConcurrencyTest {
    private static final Instant NOW = Instant.parse("2026-07-05T10:30:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void paymentIntentSaveUsesVersionReadWithAggregateAndConflictsOnStaleWriter() {
        InMemorySnapshotRepository<JacksonPaymentJson.PaymentIntentSnapshot> snapshots = new InMemorySnapshotRepository<>();
        PostgresPaymentIntentRepository repository = new PostgresPaymentIntentRepository(objectMapper, snapshots, org.mockito.Mockito.mock(JdbcOperations.class));
        PaymentIntent created = PaymentIntent.create("ord-1", "purchase", Money.fromMinorUnits(35000, "CNY"), "acct-1", NOW.plusSeconds(900), "idem-create", NOW, "cmd-create", "corr-create");
        repository.save(created);

        PaymentIntent firstWriter = repository.findById(created.paymentIntentId()).orElseThrow();
        PaymentIntent staleSecondWriter = repository.findById(created.paymentIntentId()).orElseThrow();

        firstWriter.authorize(firstWriter.amount(), "channel", "auth-1", NOW.plusSeconds(1), "cmd-auth", "cmd-auth", "corr-auth");
        repository.save(firstWriter);
        staleSecondWriter.cancel("customer", NOW.plusSeconds(2), "cmd-cancel", "cmd-cancel", "corr-cancel");

        assertThrows(OptimisticConcurrencyException.class, () -> repository.save(staleSecondWriter));
        assertEquals(2, firstWriter.version());
    }

    @Test
    void refundSaveUsesVersionReadWithAggregateAndConflictsOnStaleWriter() {
        InMemorySnapshotRepository<JacksonPaymentJson.RefundSnapshot> snapshots = new InMemorySnapshotRepository<>();
        PostgresRefundRepository repository = new PostgresRefundRepository(objectMapper, snapshots);
        PaymentIntent captured = PaymentIntent.create("ord-2", "purchase", Money.fromMinorUnits(35000, "CNY"), "acct-1", NOW.plusSeconds(900), "idem-create-refund", NOW, "cmd-create", "corr-create");
        captured.capture(captured.amount(), "channel", "txn-1", NOW.plusSeconds(1), "cmd-capture", "cmd-capture", "corr-capture");
        Refund created = Refund.request(captured, Money.fromMinorUnits(10000, "CNY"), "case-1", "ticket refund", "idem-refund", NOW.plusSeconds(2), "cmd-refund", "corr-refund");
        repository.save(created);

        Refund firstWriter = repository.findById(created.refundId()).orElseThrow();
        Refund staleSecondWriter = repository.findById(created.refundId()).orElseThrow();

        firstWriter.submitToChannel("refund-txn-1");
        repository.save(firstWriter);
        staleSecondWriter.fail("CHANNEL_DOWN", true, NOW.plusSeconds(3), "cmd-fail", "cmd-fail", "corr-fail");

        assertThrows(OptimisticConcurrencyException.class, () -> repository.save(staleSecondWriter));
        assertEquals(2, firstWriter.version());
    }

    private static final class InMemorySnapshotRepository<T> extends SnapshotRepository<T> {
        private final Map<String, Snapshot<T>> snapshots = new ConcurrentHashMap<>();

        @SuppressWarnings("unchecked")
        private InMemorySnapshotRepository() {
            super(org.mockito.Mockito.mock(JdbcOperations.class), new ObjectMapper(), "payment_intent_snapshots", (Class<T>) (Class<?>) Object.class);
        }

        @Override
        public Optional<Snapshot<T>> get(String id) {
            return Optional.ofNullable(snapshots.get(id));
        }

        @Override
        public long save(String id, long expectedVersion, T data) {
            Snapshot<T> current = snapshots.get(id);
            long currentVersion = current == null ? 0 : current.version();
            if (currentVersion != expectedVersion) {
                throw new OptimisticConcurrencyException("snapshot version conflict for " + id);
            }
            long newVersion = expectedVersion + 1;
            snapshots.put(id, new Snapshot<>(id, newVersion, data));
            return newVersion;
        }
    }
}
