package com.trainticket.platformkit.idempotency;

public class IdempotencyKeyReusedException extends RuntimeException { public IdempotencyKeyReusedException(String message) { super(message); } }
