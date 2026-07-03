package com.trainticket.payment.domain;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class IdempotencyLedger<T> {
    private final Map<String, Entry<T>> entries = new HashMap<>();

    public T recordOrReturn(String scope, String idempotencyKey, Instant now, Supplier<T> creator) {
        String ledgerKey = requireText(scope, "scope") + ":" + requireText(idempotencyKey, "idempotencyKey");
        Entry<T> existing = entries.get(ledgerKey);
        if (existing != null) {
            return existing.value();
        }
        T value = Objects.requireNonNull(creator, "creator is required").get();
        entries.put(ledgerKey, new Entry<>(value, Objects.requireNonNull(now, "now is required")));
        return value;
    }

    public int size() {
        return entries.size();
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
        return value;
    }

    private record Entry<T>(T value, Instant recordedAt) {
    }
}
