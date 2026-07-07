package com.trainticket.platformkit.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;

public class SnapshotRepository<T> {
    private static final Pattern TABLE_NAME = Pattern.compile("[a-z][a-z0-9_]*_snapshots");

    private final JdbcOperations jdbc;
    private final ObjectMapper objectMapper;
    private final String tableName;
    private final Class<T> dataType;

    public SnapshotRepository(javax.sql.DataSource dataSource, ObjectMapper objectMapper, String tableName, Class<T> dataType) {
        this(new JdbcTemplate(dataSource), objectMapper, tableName, dataType);
    }

    public SnapshotRepository(JdbcOperations jdbc, ObjectMapper objectMapper, String tableName, Class<T> dataType) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.tableName = validateTableName(tableName);
        this.dataType = Objects.requireNonNull(dataType, "dataType is required");
    }

    public Optional<Snapshot<T>> get(String id) {
        return jdbc.query("SELECT id, version, data::text AS data FROM " + tableName + " WHERE id = ?", rs -> {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(mapSnapshot(rs));
        }, requireText(id, "id"));
    }

    public long save(String id, long expectedVersion, T data) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        String json = toJson(data);
        if (expectedVersion == 0) {
            try {
                jdbc.update("INSERT INTO " + tableName + " (id, version, data) VALUES (?, 1, ?::jsonb)", requireText(id, "id"), json);
                return 1;
            } catch (DuplicateKeyException exception) {
                throw new OptimisticConcurrencyException("snapshot version conflict for " + id);
            }
        }
        int updated = jdbc.update(
            "UPDATE " + tableName + " SET version = version + 1, data = ?::jsonb, updated_at = now() WHERE id = ? AND version = ?",
            json,
            requireText(id, "id"),
            expectedVersion
        );
        if (updated == 0) {
            throw new OptimisticConcurrencyException("snapshot version conflict for " + id);
        }
        return expectedVersion + 1;
    }

    private Snapshot<T> mapSnapshot(ResultSet rs) throws SQLException {
        try {
            return new Snapshot<>(rs.getString("id"), rs.getLong("version"), objectMapper.readValue(rs.getString("data"), dataType));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("snapshot JSON could not be decoded", exception);
        }
    }

    private String toJson(T data) {
        try {
            return objectMapper.writeValueAsString(Objects.requireNonNull(data, "data is required"));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("snapshot JSON could not be encoded", exception);
        }
    }

    private static String validateTableName(String tableName) {
        String value = requireText(tableName, "tableName");
        if (!TABLE_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid snapshot table name");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
