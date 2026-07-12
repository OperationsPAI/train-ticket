package com.trainticket.postsales.application;

public class SubscribeFailedException extends RuntimeException {
    public SubscribeFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
