package com.trainticket.bookingorchestration.infrastructure.persistence;

import com.trainticket.bookingorchestration.observability.BookingSagaStageRepository;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads the nonterminal sagas back out of the snapshot table.
 *
 * <p>The predicate matches the partial index in {@code 006_booking_saga_stage_index.sql}, so the
 * periodic report reads only the in-flight sagas instead of the millions of finished ones.
 */
@Repository
@ConditionalOnBean(DataSource.class)
public class PostgresBookingSagaStageRepository implements BookingSagaStageRepository {
    private final JdbcTemplate jdbc;

    @Autowired
    public PostgresBookingSagaStageRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public java.util.List<ActiveSaga> findNonterminalSagas() {
        return jdbc.query(
            """
                SELECT id, data->>'status' AS status, updated_at
                FROM booking_saga_snapshots
                WHERE data->>'status' IN ('PLANNED', 'RISK_CHECKING', 'RESERVING', 'SEAT_ASSIGNING',
                                          'AWAITING_PAYMENT', 'CONFIRMING', 'TICKETING', 'INVOICING',
                                          'PARTIALLY_CONFIRMED', 'COMPENSATING')
                """,
            (rs, rowNum) -> new ActiveSaga(
                rs.getString("id"),
                rs.getString("status"),
                rs.getTimestamp("updated_at").toInstant()
            )
        );
    }
}
