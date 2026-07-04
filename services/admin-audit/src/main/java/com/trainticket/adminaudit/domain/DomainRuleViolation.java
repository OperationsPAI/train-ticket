package com.trainticket.adminaudit.domain;

public class DomainRuleViolation extends RuntimeException {
    public DomainRuleViolation(String message) {
        super(message);
    }
}
