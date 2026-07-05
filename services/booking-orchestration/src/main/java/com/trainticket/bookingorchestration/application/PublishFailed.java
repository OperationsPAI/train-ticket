package com.trainticket.bookingorchestration.application;

public class PublishFailed extends RuntimeException {
    public PublishFailed(String message, Throwable cause) {
        super(message, cause);
    }
    public PublishFailed(String message) {
        super(message);
    }
}
