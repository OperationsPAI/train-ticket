package com.trainticket.payment.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.payment.domain.PaymentIntent;
import com.trainticket.payment.domain.ports.PaymentIntentRepository;
import com.trainticket.platformkit.persistence.SnapshotRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
@Primary
@ConditionalOnBean(DataSource.class)
public class PostgresPaymentIntentRepository implements PaymentIntentRepository {
    private final ObjectMapper objectMapper;
    private final SnapshotRepository<JacksonPaymentJson.PaymentIntentSnapshot> snapshots;
    private final JdbcOperations jdbc;

    @Autowired
    public PostgresPaymentIntentRepository(DataSource dataSource, ObjectMapper objectMapper) {
        this(objectMapper, new SnapshotRepository<>(dataSource, objectMapper, "payment_intent_snapshots", JacksonPaymentJson.PaymentIntentSnapshot.class), new JdbcTemplate(dataSource));
    }

    PostgresPaymentIntentRepository(
        ObjectMapper objectMapper,
        SnapshotRepository<JacksonPaymentJson.PaymentIntentSnapshot> snapshots,
        JdbcOperations jdbc
    ) {
        this.objectMapper = objectMapper;
        this.snapshots = snapshots;
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PaymentIntent> findById(String paymentIntentId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
            && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            jdbc.query("SELECT id FROM payment_intent_snapshots WHERE id = ? FOR UPDATE",
                rs -> { return rs.next(); }, paymentIntentId);
        }
        return snapshots.get(paymentIntentId)
            .map(snapshot -> JacksonPaymentJson.toIntent(snapshot.data(), objectMapper).withVersion(snapshot.version()));
    }

    @Override
    public Optional<PaymentIntent> findLatestByBusinessRef(String businessRef) {
        Optional<String> id = jdbc.query(
            "SELECT id FROM payment_intent_snapshots WHERE data->>'businessRef' = ? "
                + "ORDER BY (data->'domainEvents'->0->>'occurredAt')::timestamptz DESC, id DESC LIMIT 1",
            rs -> rs.next() ? Optional.of(rs.getString("id")) : Optional.empty(),
            businessRef
        );
        return id.flatMap(this::findById);
    }

    @Override
    public Optional<PaymentIntent> findByIdempotencyKey(String idempotencyKey) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> { return rs.next(); }, "payment-create:" + idempotencyKey);
        }
        List<String> ids = jdbc.query("SELECT id FROM payment_intent_snapshots WHERE data->>'idempotencyKey'=? LIMIT 2",
            (rs, row) -> rs.getString("id"), idempotencyKey);
        if (ids.size() > 1) throw new IllegalStateException("multiple payment intents have the same creation key");
        return ids.isEmpty() ? Optional.empty() : findById(ids.getFirst());
    }

    @Override
    public List<PaymentIntent> findExpiredOpenIntents(Instant now, int limit) {
        return jdbc.query(
            "SELECT data->>'paymentIntentId' AS id FROM payment_intent_snapshots "
                + "WHERE payment_expires_at_immutable(data) <= ?::timestamp AND data->>'status' IN ('CREATED','AUTHORIZED') "
                + "ORDER BY payment_expires_at_immutable(data) ASC LIMIT ? FOR UPDATE SKIP LOCKED",
            (rs, rowNum) -> rs.getString("id"),
            now.toString(),
            limit
        ).stream().map(id -> findById(id).orElseThrow()).toList();
    }

    @Override
    public void save(PaymentIntent intent) {
        long newVersion = snapshots.save(intent.paymentIntentId(), intent.version(), JacksonPaymentJson.intentSnapshot(intent, objectMapper));
        intent.withVersion(newVersion);
    }
}
