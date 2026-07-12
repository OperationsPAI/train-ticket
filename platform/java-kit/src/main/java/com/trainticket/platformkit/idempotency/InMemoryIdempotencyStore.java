package com.trainticket.platformkit.idempotency;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemoryIdempotencyStore implements IdempotencyStore {
    private final ConcurrentMap<String, StoredResponse> responses = new ConcurrentHashMap<>();

    @Override
    public Optional<StoredResponse> find(String key) {
        return Optional.ofNullable(responses.get(key));
    }

    @Override
    public StoredResponse saveIfAbsent(String key, StoredResponse response) {
        StoredResponse existing = responses.putIfAbsent(key, response);
        return existing == null ? response : existing;
    }

    public void clear() {
        responses.clear();
    }
}
