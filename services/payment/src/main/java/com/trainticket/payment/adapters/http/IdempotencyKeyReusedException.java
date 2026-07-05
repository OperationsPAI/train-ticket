package com.trainticket.payment.adapters.http;

class IdempotencyKeyReusedException extends RuntimeException {
    IdempotencyKeyReusedException(String message) {
        super(message);
    }
}
