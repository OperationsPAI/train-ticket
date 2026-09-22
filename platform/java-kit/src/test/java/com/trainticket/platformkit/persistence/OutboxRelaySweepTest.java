package com.trainticket.platformkit.persistence;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.trainticket.platformkit.messaging.RedisStreamOperations;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;

class OutboxRelaySweepTest {
    /** Records every statement the relay issues, and reports an empty outbox. */
    private static final class RecordingJdbc {
        private final List<String> statements = new ArrayList<>();
        private final JdbcOperations jdbc = mock(JdbcOperations.class);

        RecordingJdbc() {
            when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());
            when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
                statements.add(invocation.getArgument(0));
                return 0;
            });
        }
    }

    private static OutboxRelay relayOn(RecordingJdbc recording) {
        return new OutboxRelay(recording.jdbc, mock(RedisStreamOperations.class), Duration.ofMillis(50));
    }

    @Test
    void cleanupSweepsAllThreePlatformTables() {
        RecordingJdbc recording = new RecordingJdbc();
        relayOn(recording).cleanup();
        for (String table : List.of("outbox", "processed_events", "idempotency_records")) {
            assertTrue(
                recording.statements.stream().anyMatch(sql -> sql.contains("DELETE FROM " + table + " WHERE ctid IN")),
                "no batched ctid sweep for " + table + "; statements: " + recording.statements
            );
        }
    }

    // The scheduler that services actually use is LazyRedisOutboxRelayLifecycle,
    // which drives the relay itself instead of calling start(). It called
    // pollOnce() directly and so never swept: `n_tup_del` on idempotency_records
    // was 0 in every Java service on the deployed cluster, traveler_profile
    // having reached 12811917 rows in 7855 MB.
    //
    // Polling is what must trigger the sweep, so that is what this drives.
    @Test
    void pollingEventuallySweeps() {
        RecordingJdbc recording = new RecordingJdbc();
        OutboxRelay relay = relayOn(recording);
        for (int poll = 0; poll < 20; poll++) {
            relay.pollOnceAndSweep();
        }
        assertTrue(
            recording.statements.stream().anyMatch(sql -> sql.contains("DELETE FROM idempotency_records")),
            "20 polls swept nothing; the tables grow for as long as the service runs"
        );
    }
}
