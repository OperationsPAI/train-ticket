package com.trainticket.marketingcampaign.domain;

final class Require {
    private Require() {}
    static String text(String value, String field) {
        if (value == null || value.isBlank()) throw new DomainException(field + " is required");
        return value;
    }
}
