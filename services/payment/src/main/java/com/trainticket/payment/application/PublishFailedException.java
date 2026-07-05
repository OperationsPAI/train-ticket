package com.trainticket.payment.application;

public class PublishFailedException extends RuntimeException {
    public PublishFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
