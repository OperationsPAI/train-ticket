package com.trainticket.bookingorchestration.infrastructure.persistence;

import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.bookingorchestration.application.BookingSagaRepository;
import com.trainticket.bookingorchestration.domain.BookingSaga;
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
public class PostgresBookingSagaRepository implements BookingSagaRepository {
    private final ObjectMapper objectMapper;
    private final SnapshotRepository<JacksonBookingOrchestrationJson.BookingSagaSnapshot> snapshots;
    private final JdbcOperations jdbc;

    @Autowired
    public PostgresBookingSagaRepository(DataSource dataSource, ObjectMapper objectMapper) {
        this(
            objectMapper,
            new SnapshotRepository<>(
                dataSource,
                objectMapper,
                "booking_saga_snapshots",
                JacksonBookingOrchestrationJson.BookingSagaSnapshot.class
            ),
            new JdbcTemplate(dataSource)
        );
    }

    PostgresBookingSagaRepository(
        ObjectMapper objectMapper,
        SnapshotRepository<JacksonBookingOrchestrationJson.BookingSagaSnapshot> snapshots,
        JdbcOperations jdbc
    ) {
        this.objectMapper = objectMapper;
        this.snapshots = snapshots;
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BookingSaga> findById(String sagaId) {
        return snapshots.get(sagaId)
            .map(snapshot -> JacksonBookingOrchestrationJson.toSaga(snapshot.data(), objectMapper)
                .withVersion(snapshot.version()));
    }

    @Override
    public Optional<BookingSaga> findByJourneyOrderId(String journeyOrderId) {
        return findOne(
            """
                SELECT id
                FROM booking_saga_snapshots
                WHERE data->>'journeyOrderId' = ?
                ORDER BY updated_at DESC
                LIMIT 1
                """,
            journeyOrderId
        );
    }

    @Override
    public Optional<BookingSaga> findByCorrelationId(String correlationId) {
        return findOne(
            """
                SELECT id
                FROM booking_saga_snapshots
                WHERE data->>'correlationId' = ?
                ORDER BY updated_at DESC
                LIMIT 1
                """,
            correlationId
        );
    }

    @Override
    public void save(BookingSaga saga, String correlationId) {
        long newVersion = snapshots.save(
            saga.sagaId(),
            saga.version(),
            JacksonBookingOrchestrationJson.sagaSnapshot(saga, correlationId, objectMapper)
        );
        saga.withVersion(newVersion);
    }

    private Optional<BookingSaga> findOne(String sql, String value) {
        return jdbc.query(
            sql,
            rs -> rs.next() ? findById(rs.getString("id")) : Optional.empty(),
            value
        );
    }
}
