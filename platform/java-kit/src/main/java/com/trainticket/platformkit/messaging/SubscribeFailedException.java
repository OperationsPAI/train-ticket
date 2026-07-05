package com.trainticket.platformkit.messaging;

public class SubscribeFailedException extends RuntimeException {
    public SubscribeFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
