package com.trainticket.adminaudit.adapters.http;

import java.util.Optional;

public interface IdempotencyStore {
    Optional<StoredResponse> find(String key);
    void save(String key, String fingerprint, int status, Object body);

    record StoredResponse(String fingerprint, int status, Object body) {}
}
