package com.trainticket.postsales.application;

public class IdempotencyKeyReusedException extends RuntimeException {
    public IdempotencyKeyReusedException(String key) {
        super("Idempotency-Key was reused with a different request body: " + key);
    }
}
