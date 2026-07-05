package com.trainticket.payment.adapters.http;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
class IdempotencyStore {
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    ResponseEntity<?> replayOrRecord(String method, String path, String idempotencyKey, Object requestBody, Supplier<ResponseEntity<?>> creator) {
        String key = method + ":" + path + ":" + requireText(idempotencyKey, "idempotencyKey");
        int requestHash = Objects.hashCode(requestBody);
        Entry existing = entries.get(key);
        if (existing != null) {
            if (existing.requestHash != requestHash) {
                throw new IdempotencyKeyReusedException("Idempotency-Key was reused with a different request body");
            }
            return existing.response;
        }
        ResponseEntity<?> response = creator.get();
        entries.put(key, new Entry(requestHash, response));
        return response;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(name + " is required");
        }
        return value;
    }

    private record Entry(int requestHash, ResponseEntity<?> response) {
    }
}
