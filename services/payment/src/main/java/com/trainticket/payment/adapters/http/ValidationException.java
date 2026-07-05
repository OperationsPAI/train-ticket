package com.trainticket.payment.adapters.http;

class ValidationException extends RuntimeException {
    ValidationException(String message) {
        super(message);
    }
}
