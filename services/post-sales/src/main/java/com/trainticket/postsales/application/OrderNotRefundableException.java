package com.trainticket.postsales.application;

public class OrderNotRefundableException extends RuntimeException {
    public OrderNotRefundableException() {
        super("Order is not in a refundable state");
    }
}
