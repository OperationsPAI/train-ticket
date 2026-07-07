package com.trainticket.payment.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.payment.domain.PaymentIntent;
import com.trainticket.payment.domain.ports.PaymentIntentRepository;
import com.trainticket.platformkit.persistence.SnapshotRepository;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Primary
@ConditionalOnBean(DataSource.class)
public class PostgresPaymentIntentRepository implements PaymentIntentRepository {
    private final ObjectMapper objectMapper;
    private final SnapshotRepository<JacksonPaymentJson.PaymentIntentSnapshot> snapshots;
    private final JdbcOperations jdbc;

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
        return snapshots.get(paymentIntentId)
            .map(snapshot -> JacksonPaymentJson.toIntent(snapshot.data(), objectMapper).withVersion(snapshot.version()));
    }

    @Override
    public Optional<PaymentIntent> findLatestByBusinessRef(String businessRef) {
        return jdbc.query(
            "SELECT data->>'paymentIntentId' AS id FROM payment_intent_snapshots WHERE data->>'businessRef' = ? ORDER BY data->>'paymentIntentId' DESC LIMIT 1",
            rs -> rs.next() ? findById(rs.getString("id")) : Optional.empty(),
            businessRef
        );
    }

    @Override
    public void save(PaymentIntent intent) {
        long newVersion = snapshots.save(intent.paymentIntentId(), intent.version(), JacksonPaymentJson.intentSnapshot(intent, objectMapper));
        intent.withVersion(newVersion);
    }
}
