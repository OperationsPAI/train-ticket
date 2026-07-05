package com.trainticket.platformkit.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainticket.platformkit.messaging.UuidV7;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Supplier;

public final class IdempotencyService<T> {
    private final IdempotencyStore<T> store;
    private final ObjectMapper mapper;
    public IdempotencyService(IdempotencyStore<T> store, ObjectMapper mapper) { this.store = Objects.requireNonNull(store); this.mapper = Objects.requireNonNull(mapper); }
    public T execute(String key, Object request, Supplier<T> handler) {
        validateKey(key);
        String fingerprint = fingerprint(request);
        return store.find(key).map(entry -> {
            if (!entry.requestFingerprint().equals(fingerprint)) throw new IdempotencyKeyReusedException("Idempotency-Key was reused with a different request body");
            return entry.response();
        }).orElseGet(() -> { T response = handler.get(); store.save(key, fingerprint, response); return response; });
    }
    public static void validateKey(String key) { if (key == null || key.isBlank() || !UuidV7.isUuidV7(key)) throw new IllegalArgumentException("Idempotency-Key must be a UUID v7"); }
    public String fingerprint(Object request) {
        try { return sha256(mapper.writeValueAsString(request)); } catch (JsonProcessingException ex) { throw new IllegalArgumentException("Request body could not be fingerprinted", ex); }
    }
    private static String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); } }
}
