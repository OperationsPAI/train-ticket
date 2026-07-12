package com.trainticket.journeyorder.domain;

public enum AccountOrderState {
    ACTIVE,
    FROZEN,
    CLOSED;

    public boolean permitsOrderCreation() {
        return this == ACTIVE;
    }
}
