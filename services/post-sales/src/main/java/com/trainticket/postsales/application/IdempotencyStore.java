package com.trainticket.postsales.application;

import java.util.Optional;
import java.util.function.Supplier;

public interface IdempotencyStore {
    <T> IdempotentResult<T> execute(String key, String requestFingerprint, Supplier<T> supplier);
    Optional<Object> get(String key);

    record IdempotentResult<T>(T value, boolean replay) { }
}
