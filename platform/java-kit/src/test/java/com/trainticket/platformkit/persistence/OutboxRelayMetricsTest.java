package com.trainticket.platformkit.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.trainticket.platformkit.messaging.RedisStreamOperations;
import com.trainticket.platformkit.observability.OutboxMetrics;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;

class OutboxRelayMetricsTest {
    @Test
    void recordsHeadSampleAgeAndSuccessfulPublishWithoutFullCountQuery() throws Exception {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        RedisStreamOperations streams = mock(RedisStreamOperations.class);
        RecordingOutboxMetrics metrics = new RecordingOutboxMetrics();
        Instant createdAt = Instant.now().minusSeconds(9);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            java.sql.ResultSet row = mock(java.sql.ResultSet.class);
            when(row.getLong("seq")).thenReturn(7L);
            when(row.getString("stream")).thenReturn("events:payment");
            when(row.getString("envelope")).thenReturn("{}");
            when(row.getTimestamp("created_at")).thenReturn(Timestamp.from(createdAt));
            return List.of(mapper.mapRow(row, 0));
        });
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        try (OutboxRelay relay = new OutboxRelay(jdbc, streams, Duration.ofMillis(50), metrics)) {
            assertThat(relay.pollOnce()).isEqualTo(1);
        }

        assertThat(metrics.sampleRows).isEqualTo(1);
        assertThat(metrics.oldestAge).isBetween(Duration.ofSeconds(8), Duration.ofSeconds(11));
        assertThat(metrics.publishedRows).isEqualTo(1);
        assertThat(metrics.failures).isEmpty();
        org.mockito.Mockito.verify(jdbc).query(
            org.mockito.ArgumentMatchers.contains("LIMIT 100"),
            any(RowMapper.class)
        );
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.never()).queryForObject(
            org.mockito.ArgumentMatchers.contains("count("),
            org.mockito.ArgumentMatchers.eq(Long.class)
        );
    }

    @Test
    void classifiesPublishFailureAndDoesNotClaimEventsWerePublished() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        RedisStreamOperations streams = mock(RedisStreamOperations.class);
        RecordingOutboxMetrics metrics = new RecordingOutboxMetrics();
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            java.sql.ResultSet row = mock(java.sql.ResultSet.class);
            when(row.getLong("seq")).thenReturn(9L);
            when(row.getString("stream")).thenReturn("events:payment");
            when(row.getString("envelope")).thenReturn("{}");
            when(row.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.now()));
            return List.of(mapper.mapRow(row, 0));
        });
        doThrow(new IllegalStateException("redis unavailable"))
            .when(streams).publishBatch(any());

        try (OutboxRelay relay = new OutboxRelay(jdbc, streams, Duration.ofMillis(50), metrics)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(relay::pollOnce)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("redis unavailable");
        }

        assertThat(metrics.failures).containsExactly(OutboxMetrics.FailureStage.PUBLISH);
        assertThat(metrics.publishedRows).isZero();
    }

    private static final class RecordingOutboxMetrics implements OutboxMetrics {
        private int sampleRows;
        private Duration oldestAge = Duration.ZERO;
        private int publishedRows;
        private final List<FailureStage> failures = new ArrayList<>();

        @Override
        public void observeUnpublishedSample(int rows, Duration oldestAge) {
            this.sampleRows = rows;
            this.oldestAge = oldestAge;
        }

        @Override
        public void recordPublished(int rows) {
            publishedRows += rows;
        }

        @Override
        public void recordFailure(FailureStage stage) {
            failures.add(stage);
        }
    }
}
