package com.trainticket.walletpromotion.domain;

public enum PromotionStatus {
    ISSUED, RESERVED, REDEEMED, RELEASED, EXPIRED, REVOKED, REVERSED;
    public boolean terminal() { return this == REDEEMED || this == EXPIRED || this == REVOKED || this == REVERSED; }
    public boolean canTransitionTo(PromotionStatus next) {
        return switch (this) {
            case ISSUED -> next == RESERVED || next == REDEEMED || next == EXPIRED || next == REVOKED;
            case RESERVED -> next == REDEEMED || next == RELEASED || next == EXPIRED;
            case RELEASED -> next == RESERVED || next == REDEEMED || next == EXPIRED;
            case REDEEMED -> next == REVERSED;
            case EXPIRED, REVOKED, REVERSED -> false;
        };
    }
}
