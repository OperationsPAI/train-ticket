package com.trainticket.postsales.application;

public class PostSalesConcurrencyException extends RuntimeException {
    public PostSalesConcurrencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
