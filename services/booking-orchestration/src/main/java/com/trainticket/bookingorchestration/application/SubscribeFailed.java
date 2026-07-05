package com.trainticket.bookingorchestration.application;

public class SubscribeFailed extends RuntimeException {
    public SubscribeFailed(String message, Throwable cause) {
        super(message, cause);
    }
    public SubscribeFailed(String message) {
        super(message);
    }
}
