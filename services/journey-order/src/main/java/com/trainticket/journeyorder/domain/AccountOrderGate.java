package com.trainticket.journeyorder.domain;

import java.util.Optional;

public final class AccountOrderGate {
    private AccountOrderGate() {
    }

    public static void assertCanCreateOrder(String accountId, Optional<AccountOrderState> projectedState) {
        projectedState.ifPresent(state -> {
            if (!state.permitsOrderCreation()) {
                throw new DomainRuleViolation("Account " + accountId + " is " + state.name() + " and cannot place new journey orders");
            }
        });
    }
}
