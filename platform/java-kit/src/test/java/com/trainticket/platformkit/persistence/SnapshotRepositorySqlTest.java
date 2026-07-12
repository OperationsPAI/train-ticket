package com.trainticket.platformkit.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcOperations;

class SnapshotRepositorySqlTest {
    @Test
    void insertAndUpdateUseOptimisticVersionChecks() {
        JdbcOperations jdbc = org.mockito.Mockito.mock(JdbcOperations.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        SnapshotRepository<Map> repository = new SnapshotRepository<>(jdbc, new ObjectMapper(), "payment_intent_snapshots", Map.class);

        assertThat(repository.save("pi-1", 0, Map.of("status", "CREATED"))).isEqualTo(1);
        assertThat(repository.save("pi-1", 1, Map.of("status", "CAPTURED"))).isEqualTo(2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update("INSERT INTO payment_intent_snapshots (id, version, data) VALUES (?, 1, ?::jsonb)", "pi-1", "{\"status\":\"CREATED\"}");
        verify(jdbc).update(org.mockito.Mockito.eq("UPDATE payment_intent_snapshots SET version = version + 1, data = ?::jsonb, updated_at = now() WHERE id = ? AND version = ?"), args.capture());
        assertThat(args.getValue()).containsExactly("{\"status\":\"CAPTURED\"}", "pi-1", 1L);
    }

    @Test
    void zeroUpdatedRowsAreVersionConflicts() {
        JdbcOperations jdbc = org.mockito.Mockito.mock(JdbcOperations.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        SnapshotRepository<Map> repository = new SnapshotRepository<>(jdbc, new ObjectMapper(), "payment_intent_snapshots", Map.class);

        assertThatThrownBy(() -> repository.save("pi-1", 5, Map.of("status", "CAPTURED")))
            .isInstanceOf(OptimisticConcurrencyException.class);
    }

    @Test
    void duplicateInsertIsVersionConflict() {
        JdbcOperations jdbc = org.mockito.Mockito.mock(JdbcOperations.class);
        doThrow(new DuplicateKeyException("duplicate")).when(jdbc).update(anyString(), any(Object[].class));
        SnapshotRepository<Map> repository = new SnapshotRepository<>(jdbc, new ObjectMapper(), "payment_intent_snapshots", Map.class);

        assertThatThrownBy(() -> repository.save("pi-1", 0, Map.of("status", "CREATED")))
            .isInstanceOf(OptimisticConcurrencyException.class);
    }
}
