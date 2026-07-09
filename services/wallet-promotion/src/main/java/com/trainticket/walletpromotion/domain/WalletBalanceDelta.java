package com.trainticket.walletpromotion.domain;

public record WalletBalanceDelta(String walletAccountId, BalanceType balanceType, Money availableDelta, Money reservedDelta, Money redeemedDelta, Money availableBalanceAfter, Money reservedBalanceAfter, String ledgerEntryId) {}
