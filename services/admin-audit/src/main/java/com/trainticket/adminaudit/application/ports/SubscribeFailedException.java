package com.trainticket.adminaudit.application.ports;

public class SubscribeFailedException extends RuntimeException {
    public SubscribeFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
