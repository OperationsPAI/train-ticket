package com.trainticket.platformkit.idempotency;

import java.util.Optional;

public interface IdempotencyStore<T> {
    Optional<Entry<T>> find(String key);
    void save(String key, String requestFingerprint, T response);
    record Entry<T>(String requestFingerprint, T response) {}
}
