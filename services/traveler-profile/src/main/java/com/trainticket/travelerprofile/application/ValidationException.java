package com.trainticket.travelerprofile.application;

import java.util.Map;

public class ValidationException extends RuntimeException {
    private final Map<String, String> details;

    public ValidationException(String message, Map<String, String> details) {
        super(message);
        this.details = Map.copyOf(details);
    }

    public Map<String, String> details() {
        return details;
    }
}
