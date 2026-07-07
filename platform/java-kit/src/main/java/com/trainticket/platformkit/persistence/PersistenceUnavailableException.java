package com.trainticket.platformkit.persistence;

public class PersistenceUnavailableException extends RuntimeException {
    public PersistenceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
