package com.trainticket.bookingorchestration.infrastructure.persistence;

import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.bookingorchestration.application.SegmentBookingRepository;
import com.trainticket.bookingorchestration.domain.SegmentBooking;
import com.trainticket.platformkit.persistence.SnapshotRepository;
import java.util.List;
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
public class PostgresSegmentBookingRepository implements SegmentBookingRepository {
    private final ObjectMapper objectMapper;
    private final SnapshotRepository<JacksonBookingOrchestrationJson.SegmentBookingSnapshot> snapshots;
    private final JdbcOperations jdbc;

    @Autowired
    public PostgresSegmentBookingRepository(DataSource dataSource, ObjectMapper objectMapper) {
        this(
            objectMapper,
            new SnapshotRepository<>(
                dataSource,
                objectMapper,
                "segment_booking_snapshots",
                JacksonBookingOrchestrationJson.SegmentBookingSnapshot.class
            ),
            new JdbcTemplate(dataSource)
        );
    }

    PostgresSegmentBookingRepository(
        ObjectMapper objectMapper,
        SnapshotRepository<JacksonBookingOrchestrationJson.SegmentBookingSnapshot> snapshots,
        JdbcOperations jdbc
    ) {
        this.objectMapper = objectMapper;
        this.snapshots = snapshots;
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SegmentBookingRecord> findById(String segmentBookingId) {
        return snapshots.get(segmentBookingId)
            .map(snapshot -> new SegmentBookingRecord(
                JacksonBookingOrchestrationJson.toSegmentBooking(snapshot.data(), objectMapper)
                    .withVersion(snapshot.version()),
                snapshot.data().data().path("sagaId").asText(null)
            ));
    }

    @Override
    public Optional<SegmentBookingRecord> findByIdempotencyKey(String idempotencyKey) {
        return findOne(
            """
                SELECT id
                FROM segment_booking_snapshots
                WHERE data->>'idempotencyKey' = ?
                ORDER BY updated_at DESC
                LIMIT 1
                """,
            idempotencyKey
        );
    }

    @Override
    public Optional<SegmentBookingRecord> findByCapacityHoldId(String capacityHoldId) {
        return findOne(
            """
                SELECT id
                FROM segment_booking_snapshots
                WHERE data->>'capacityHoldId' = ?
                ORDER BY updated_at DESC
                LIMIT 1
                """,
            capacityHoldId
        );
    }

    @Override
    public List<SegmentBookingRecord> findBySagaId(String sagaId) {
        return jdbc.query(
            """
                SELECT id
                FROM segment_booking_snapshots
                WHERE data->>'sagaId' = ?
                ORDER BY id
                """,
            (rs, rowNum) -> findById(rs.getString("id")).orElseThrow(),
            sagaId
        );
    }

    @Override
    public void save(SegmentBooking booking, String sagaId) {
        long newVersion = snapshots.save(
            booking.segmentBookingId(),
            booking.version(),
            JacksonBookingOrchestrationJson.segmentSnapshot(booking, sagaId, objectMapper)
        );
        booking.withVersion(newVersion);
    }

    private Optional<SegmentBookingRecord> findOne(String sql, String value) {
        return jdbc.query(
            sql,
            rs -> rs.next() ? findById(rs.getString("id")) : Optional.empty(),
            value
        );
    }
}
