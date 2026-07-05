package com.trainticket.adminaudit.adapters.http;

import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.adminaudit.application.ValidationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyService {
    private final com.trainticket.platformkit.idempotency.IdempotencyStore store;
    private final ObjectMapper objectMapper;

    @Autowired
    public IdempotencyService(com.trainticket.platformkit.idempotency.IdempotencyStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    public ResponseEntity<Object> execute(String key, Object request, int successStatus, Supplier<Object> action) {
        if (key == null || key.isBlank()) {
            throw new ValidationException("Idempotency-Key header is required");
        }
        String fingerprint = fingerprint(request);
        return store.find(key)
            .map(stored -> replayOrReject(stored, fingerprint))
            .orElseGet(() -> {
                Object body = action.get();
                byte[] bytes = serialize(body);
                store.saveIfAbsent(key, new com.trainticket.platformkit.idempotency.IdempotencyStore.StoredResponse(fingerprint, successStatus, "application/json", java.util.Map.of(), bytes));
                return ResponseEntity.status(successStatus).body(body);
            });
    }

    private ResponseEntity<Object> replayOrReject(com.trainticket.platformkit.idempotency.IdempotencyStore.StoredResponse stored, String fingerprint) {
        if (!stored.fingerprint().equals(fingerprint)) {
            throw new com.trainticket.platformkit.idempotency.IdempotencyKeyReusedException();
        }
        try {
            return ResponseEntity.status(stored.status()).body(objectMapper.readValue(stored.body(), Object.class));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("could not replay idempotent response", exception);
        }
    }

    private byte[] serialize(Object body) {
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("could not serialize idempotent response", exception);
        }
    }

    private String fingerprint(Object request) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(request);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("could not fingerprint request", exception);
        }
    }
}
