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
        return responses.compute(key, (ignored, existing) -> existing == null
            || ((existing.status() >= 500 || existing.status()==409 || existing.status()==412) && existing.fingerprint().equals(response.fingerprint())) ? response : existing);
    }

    public void clear() {
        responses.clear();
    }
}
