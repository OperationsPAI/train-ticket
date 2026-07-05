package com.trainticket.adminaudit.application.ports;

public class PublishFailedException extends RuntimeException {
    public PublishFailedException(String message, Throwable cause) {
        super(message, cause);
    }

    public PublishFailedException(String message) {
        super(message);
    }
}
