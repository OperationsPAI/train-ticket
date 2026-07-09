package com.trainticket.walletpromotion.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WalletAccount(String walletAccountId, String accountId, List<WalletBalance> balances, String lastLedgerEntryId, Instant updatedAt, long version) {
    public WalletAccount {
        if (walletAccountId == null || walletAccountId.isBlank()) throw new DomainException("walletAccountId is required");
        if (accountId == null || accountId.isBlank()) throw new DomainException("accountId is required");
        balances = balances == null ? List.of() : List.copyOf(balances);
        updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
    }
    public static WalletAccount create(String id, String accountId, Instant now) { return new WalletAccount(id, accountId, List.of(), null, now, 0); }
    public WalletAccount applyLedger(BalanceType balanceType, String currency, Money availableDelta, Money reservedDelta, Money redeemedDelta, String ledgerEntryId, Instant now) {
        if (ledgerEntryId == null || ledgerEntryId.isBlank()) throw new DomainException("ledgerEntryId is required");
        availableDelta.requireSameCurrency(reservedDelta); availableDelta.requireSameCurrency(redeemedDelta);
        if (!availableDelta.currency().equals(currency.toUpperCase())) throw new DomainException("ledger currency mismatch");
        List<WalletBalance> next = new ArrayList<>();
        boolean found = false;
        for (WalletBalance balance : balances) {
            if (balance.balanceType() == balanceType && balance.currency().equals(currency.toUpperCase())) {
                next.add(balance.apply(availableDelta, reservedDelta, redeemedDelta));
                found = true;
            } else next.add(balance);
        }
        if (!found) {
            next.add(new WalletBalance(balanceType, Money.zero(currency), Money.zero(currency), Money.zero(currency)).apply(availableDelta, reservedDelta, redeemedDelta));
        }
        return new WalletAccount(walletAccountId, accountId, next, ledgerEntryId, now, version + 1);
    }
}
