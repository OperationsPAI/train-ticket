package com.trainticket.platformkit.idempotency;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryIdempotencyStore<T> implements IdempotencyStore<T> {
    private final ConcurrentMap<String, Entry<T>> entries = new ConcurrentHashMap<>();
    @Override public Optional<Entry<T>> find(String key) { return Optional.ofNullable(entries.get(key)); }
    @Override public void save(String key, String requestFingerprint, T response) { entries.put(key, new Entry<>(requestFingerprint, response)); }
}
