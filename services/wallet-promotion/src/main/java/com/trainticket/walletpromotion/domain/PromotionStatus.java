package com.trainticket.walletpromotion.domain;

public enum PromotionStatus {
    ISSUED, RESERVED, REDEEMED, RELEASED, EXPIRED, REVOKED, REVERSED;
    public boolean terminal() { return this == EXPIRED || this == REVOKED; }
}
