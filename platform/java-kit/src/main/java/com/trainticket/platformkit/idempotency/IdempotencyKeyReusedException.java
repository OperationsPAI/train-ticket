package com.trainticket.platformkit.idempotency;

public class IdempotencyKeyReusedException extends RuntimeException {
    public IdempotencyKeyReusedException() {
        super("Idempotency-Key was reused with a different request body");
    }
}
