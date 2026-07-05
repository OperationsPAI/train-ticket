package com.trainticket.postsales.application;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class MemoryIdempotencyStore implements IdempotencyStore {
    private final ConcurrentMap<String, Entry> results = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <T> IdempotentResult<T> execute(String key, String requestFingerprint, Supplier<T> supplier) {
        String fingerprint = Objects.requireNonNull(requestFingerprint, "requestFingerprint is required");
        Entry existing = results.get(key);
        if (existing != null) {
            ensureSameFingerprint(key, fingerprint, existing);
            return new IdempotentResult<>((T) existing.result(), true);
        }
        Object created = supplier.get();
        Entry createdEntry = new Entry(fingerprint, created);
        Entry raced = results.putIfAbsent(key, createdEntry);
        if (raced != null) {
            ensureSameFingerprint(key, fingerprint, raced);
            return new IdempotentResult<>((T) raced.result(), true);
        }
        return new IdempotentResult<>((T) created, false);
    }

    @Override
    public Optional<Object> get(String key) {
        return Optional.ofNullable(results.get(key)).map(Entry::result);
    }

    private static void ensureSameFingerprint(String key, String fingerprint, Entry entry) {
        if (!entry.requestFingerprint().equals(fingerprint)) {
            throw new IdempotencyKeyReusedException(key);
        }
    }

    private record Entry(String requestFingerprint, Object result) { }
}
