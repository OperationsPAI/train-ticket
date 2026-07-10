package com.trainticket.postsales.application;

public class RefundAlreadyInProgressException extends RuntimeException {
    private final String existingCaseId;

    public RefundAlreadyInProgressException(String existingCaseId) {
        super("A refund or change case is already in progress for this order");
        this.existingCaseId = existingCaseId;
    }

    public String existingCaseId() {
        return existingCaseId;
    }
}
