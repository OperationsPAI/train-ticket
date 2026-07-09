package com.trainticket.walletpromotion.domain;

public record WalletBalance(BalanceType balanceType, Money availableBalance, Money reservedBalance, Money redeemedBalance) {
    public WalletBalance {
        availableBalance.requireNonNegative("availableBalance");
        reservedBalance.requireNonNegative("reservedBalance");
        availableBalance.requireSameCurrency(reservedBalance);
        availableBalance.requireSameCurrency(redeemedBalance);
    }
    public String currency() { return availableBalance.currency(); }
    public WalletBalance apply(Money availableDelta, Money reservedDelta, Money redeemedDelta) {
        Money available = availableBalance.plus(availableDelta);
        Money reserved = reservedBalance.plus(reservedDelta);
        Money redeemed = redeemedBalance.plus(redeemedDelta);
        return new WalletBalance(balanceType, available, reserved, redeemed);
    }
}
