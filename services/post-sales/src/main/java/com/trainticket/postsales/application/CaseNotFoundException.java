package com.trainticket.postsales.application;

public class CaseNotFoundException extends RuntimeException {
    public CaseNotFoundException(String caseId) {
        super("post-sales case not found: " + caseId);
    }
}
