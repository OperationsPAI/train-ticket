package com.trainticket.financesettlement.application;

public class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }
}
