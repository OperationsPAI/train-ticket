package com.trainticket.financesettlement.application;

public class SubscribeFailedException extends RuntimeException {
    public SubscribeFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
