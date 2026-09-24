package com.trainticket.bookingorchestration.infrastructure.persistence;

import com.trainticket.bookingorchestration.application.BookingSagaStepDeadlineRepository;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Primary
@ConditionalOnBean(DataSource.class)
public class PostgresBookingSagaStepDeadlineRepository implements BookingSagaStepDeadlineRepository {
    private final JdbcTemplate jdbc;

    @Autowired
    public PostgresBookingSagaStepDeadlineRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void schedule(String sagaId, String stepIdempotencyKey, Instant deadline) {
        jdbc.update(
            """
                INSERT INTO booking_saga_step_deadlines (saga_id, step_key, deadline_ms)
                VALUES (?, ?, ?)
                ON CONFLICT (saga_id, step_key) DO UPDATE SET deadline_ms = EXCLUDED.deadline_ms
                """,
            sagaId,
            stepIdempotencyKey,
            deadline.toEpochMilli()
        );
    }

    @Override
    public void clear(String sagaId, String stepIdempotencyKey) {
        jdbc.update("DELETE FROM booking_saga_step_deadlines WHERE saga_id = ? AND step_key = ?",
            sagaId, stepIdempotencyKey);
    }

    @Override
    public void clearSaga(String sagaId) {
        jdbc.update("DELETE FROM booking_saga_step_deadlines WHERE saga_id = ?", sagaId);
    }

    @Override
    public List<BookingSagaStepDeadline> findDue(Instant now, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return jdbc.query(
            """
                SELECT saga_id, step_key, deadline_ms
                FROM booking_saga_step_deadlines
                WHERE deadline_ms <= ?
                ORDER BY deadline_ms
                LIMIT ?
                """,
            (rs, rowNum) -> new BookingSagaStepDeadline(
                rs.getString("saga_id"),
                rs.getString("step_key"),
                Instant.ofEpochMilli(rs.getLong("deadline_ms"))
            ),
            now.toEpochMilli(),
            limit
        );
    }
}
