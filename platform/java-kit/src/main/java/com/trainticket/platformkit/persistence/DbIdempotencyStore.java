package com.trainticket.platformkit.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trainticket.platformkit.idempotency.IdempotencyStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;

public class DbIdempotencyStore implements IdempotencyStore {
    private static final TypeReference<Map<String, List<String>>> HEADERS_TYPE = new TypeReference<>() {
    };

    private final JdbcOperations jdbc;
    private final ObjectMapper objectMapper;

    public DbIdempotencyStore(javax.sql.DataSource dataSource, ObjectMapper objectMapper) {
        this(new JdbcTemplate(dataSource), objectMapper);
    }

    public DbIdempotencyStore(JdbcOperations jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
    }

    @Override
    public Optional<StoredResponse> find(String key) {
        return jdbc.query("SELECT request_hash, status_code, response_body::text AS response_body FROM idempotency_records WHERE key = ?", rs -> {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(toStoredResponse(rs));
        }, requireText(key, "key"));
    }

    @Override
    public StoredResponse saveIfAbsent(String key, StoredResponse response) {
        try {
            jdbc.update(
                "INSERT INTO idempotency_records(key, request_hash, status_code, response_body) VALUES (?, ?, ?, ?::jsonb)",
                requireText(key, "key"),
                response.fingerprint(),
                response.status(),
                toJson(response)
            );
            return response;
        } catch (DuplicateKeyException exception) {
            return find(key).orElseThrow(() -> exception);
        }
    }

    private StoredResponse toStoredResponse(ResultSet rs) throws SQLException {
        try {
            JsonNode root = objectMapper.readTree(rs.getString("response_body"));
            String contentType = root.path("contentType").isNull() ? null : root.path("contentType").asText(null);
            Map<String, List<String>> headers = objectMapper.convertValue(root.path("headers"), HEADERS_TYPE);
            byte[] body = Base64.getDecoder().decode(root.path("bodyBase64").asText(""));
            return new StoredResponse(rs.getString("request_hash"), rs.getInt("status_code"), contentType, headers, body);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalStateException("idempotency record could not be decoded", exception);
        }
    }

    private String toJson(StoredResponse response) {
        ObjectNode root = objectMapper.createObjectNode();
        if (response.contentType() == null) {
            root.putNull("contentType");
        } else {
            root.put("contentType", response.contentType());
        }
        root.set("headers", objectMapper.valueToTree(response.headers()));
        root.put("bodyBase64", Base64.getEncoder().encodeToString(response.body()));
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("idempotency record could not be encoded", exception);
        }
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
