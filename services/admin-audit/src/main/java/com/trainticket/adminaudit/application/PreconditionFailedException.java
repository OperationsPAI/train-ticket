package com.trainticket.adminaudit.application;

public class PreconditionFailedException extends RuntimeException {
    public PreconditionFailedException(String message) { super(message); }
    public PreconditionFailedException(String message, Throwable cause) { super(message, cause); }
}
