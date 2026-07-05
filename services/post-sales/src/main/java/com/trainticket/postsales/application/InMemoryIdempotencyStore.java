package com.trainticket.postsales.application;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class InMemoryIdempotencyStore implements IdempotencyStore {
    private final ConcurrentMap<String, Object> results = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <T> IdempotentResult<T> execute(String key, Supplier<T> supplier) {
        Object existing = results.get(key);
        if (existing != null) {
            return new IdempotentResult<>((T) existing, true);
        }
        Object created = supplier.get();
        Object raced = results.putIfAbsent(key, created);
        if (raced != null) {
            return new IdempotentResult<>((T) raced, true);
        }
        return new IdempotentResult<>((T) created, false);
    }

    @Override
    public Optional<Object> get(String key) {
        return Optional.ofNullable(results.get(key));
    }
}
