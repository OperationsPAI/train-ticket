package com.trainticket.bookingorchestration.infrastructure.persistence;

import com.trainticket.bookingorchestration.application.PaymentIntentSagaReferenceRepository;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Primary
@ConditionalOnBean(DataSource.class)
public class PostgresPaymentIntentSagaReferenceRepository implements PaymentIntentSagaReferenceRepository {
    private final JdbcTemplate jdbc;

    public PostgresPaymentIntentSagaReferenceRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Optional<String> findSagaId(String paymentIntentId) {
        return jdbc.query(
            "SELECT saga_id FROM payment_intent_saga_refs WHERE payment_intent_id = ?",
            rs -> rs.next() ? Optional.of(rs.getString("saga_id")) : Optional.empty(),
            paymentIntentId
        );
    }

    @Override
    public void saveIfAbsent(String paymentIntentId, String sagaId) {
        jdbc.update(
            "INSERT INTO payment_intent_saga_refs(payment_intent_id, saga_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
            paymentIntentId,
            sagaId
        );
    }
}
