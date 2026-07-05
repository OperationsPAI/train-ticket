package com.trainticket.adminaudit.adapters.http;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

@Component
public class InMemoryIdempotencyStore implements IdempotencyStore {
    private final ConcurrentMap<String, StoredResponse> responses = new ConcurrentHashMap<>();

    @Override
    public Optional<StoredResponse> find(String key) {
        return Optional.ofNullable(responses.get(key));
    }

    @Override
    public void save(String key, String fingerprint, int status, Object body) {
        responses.putIfAbsent(key, new StoredResponse(fingerprint, status, body));
    }
}
