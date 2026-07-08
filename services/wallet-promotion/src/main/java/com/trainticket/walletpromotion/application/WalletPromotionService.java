package com.trainticket.walletpromotion.application;

import com.trainticket.platformkit.http.ApiErrorCode;
import com.trainticket.platformkit.http.ApiException;
import com.trainticket.walletpromotion.domain.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletPromotionService {
    private final PromotionRepository repository;
    private final Clock clock;

    public WalletPromotionService(PromotionRepository repository) { this(repository, Clock.systemUTC()); }
    WalletPromotionService(PromotionRepository repository, Clock clock) { this.repository = repository; this.clock = clock; }

    @Transactional
    public PromotionInstrument issue(IssueBenefitCommand c) {
        Instant now = clock.instant();
        String walletId = repository.findWalletByAccountId(c.accountId()).map(WalletAccount::walletAccountId).orElseGet(() -> "wac-" + UUID.randomUUID());
        PromotionInstrument benefit = PromotionInstrument.issue("ben-" + UUID.randomUUID(), c.accountId(), walletId, c.benefitType(), c.balanceType(), c.amount(), c.issuanceSource(), c.caseId(), c.applicableScope(), c.redemptionRule(), c.revocationRule(), c.validFrom(), c.validUntil(), c.businessReason(), now);
        WalletAccount wallet = wallet(c.accountId(), walletId, now).applyLedger(c.balanceType(), c.amount().currency(), c.amount(), Money.zero(c.amount().currency()), Money.zero(c.amount().currency()), ledgerId(), now);
        repository.saveMutation(benefit, wallet, EventFactory.issued(benefit, delta(wallet, c.balanceType(), c.amount().currency(), c.amount(), Money.zero(c.amount().currency()), Money.zero(c.amount().currency())), now), null, null);
        return benefit;
    }

    public PromotionInstrument getBenefit(String id) { return repository.findBenefit(id).orElseThrow(() -> notFound("benefit not found")); }
    public WalletAccount getWallet(String accountId) { return repository.findWalletByAccountId(accountId).orElseThrow(() -> notFound("wallet account not found")); }
    public PagedBenefits list(String accountId, PromotionStatus status, BenefitType type, int limit, int offset) {
        if (accountId == null || accountId.isBlank()) throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "byAccountId is required");
        int bounded = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 100)); int safeOffset = Math.max(0, offset);
        return new PagedBenefits(repository.listBenefits(accountId, status, type, bounded, safeOffset), repository.countBenefits(accountId, status, type), bounded, safeOffset);
    }

    @Transactional public PromotionInstrument reserve(String id, ReserveBenefitCommand c) {
        Instant now = clock.instant(); PromotionInstrument before = getBenefit(id); PromotionInstrument after = mapDomain(() -> before.reserve(c.amount(), c.businessReason(), now));
        Money neg = c.amount().negate(); WalletAccount wallet = getWallet(before.accountId()).applyLedger(before.balanceType(), c.amount().currency(), neg, c.amount(), Money.zero(c.amount().currency()), ledgerId(), now);
        repository.saveMutation(after, wallet, EventFactory.reserved(after, c, delta(wallet, before.balanceType(), c.amount().currency(), neg, c.amount(), Money.zero(c.amount().currency())), now), null, null); return after;
    }
    @Transactional public RedeemResult redeem(String id, RedeemBenefitCommand c) {
        Instant now = clock.instant(); PromotionInstrument before = getBenefit(id);
        Optional<BenefitRedemption> existing = repository.findRedemptionByReason(id, c.businessReason());
        if (existing.isPresent()) {
            BenefitRedemption r = existing.get();
            if (r.amount().equals(c.amount()) && r.redemptionRef().equals(c.redemptionRef())) return new RedeemResult(before, r);
            throw new ApiException(ApiErrorCode.CONFLICT, "benefit already redeemed for businessReason");
        }
        boolean fromReservation = c.reservationRef() != null && !c.reservationRef().isBlank();
        PromotionInstrument after = mapDomain(() -> before.redeem(c.amount(), c.businessReason(), now, fromReservation));
        Money availableDelta = fromReservation ? Money.zero(c.amount().currency()) : c.amount().negate(); Money reservedDelta = fromReservation ? c.amount().negate() : Money.zero(c.amount().currency());
        WalletAccount wallet = getWallet(before.accountId()).applyLedger(before.balanceType(), c.amount().currency(), availableDelta, reservedDelta, c.amount(), ledgerId(), now);
        BenefitRedemption redemption = new BenefitRedemption("brd-" + UUID.randomUUID(), id, before.accountId(), c.amount(), c.redemptionRef(), c.businessReason(), now, 0);
        repository.saveMutation(after, wallet, EventFactory.redeemed(after, redemption, c.reservationRef(), delta(wallet, before.balanceType(), c.amount().currency(), availableDelta, reservedDelta, c.amount()), now), redemption, null);
        return new RedeemResult(after, redemption);
    }
    @Transactional public PromotionInstrument release(String id, ReleaseBenefitCommand c) {
        Instant now = clock.instant(); PromotionInstrument before = getBenefit(id); PromotionInstrument after = mapDomain(() -> before.release(c.amount(), c.businessReason(), now));
        WalletAccount wallet = getWallet(before.accountId()).applyLedger(before.balanceType(), c.amount().currency(), c.amount(), c.amount().negate(), Money.zero(c.amount().currency()), ledgerId(), now);
        repository.saveMutation(after, wallet, EventFactory.released(after, c, delta(wallet, before.balanceType(), c.amount().currency(), c.amount(), c.amount().negate(), Money.zero(c.amount().currency())), now), null, null); return after;
    }
    @Transactional public PromotionInstrument revoke(String id, RevokeBenefitCommand c) {
        Instant now = c.effectiveAt() == null ? clock.instant() : c.effectiveAt(); PromotionInstrument before = getBenefit(id); Money removed = before.availableAmount(); PromotionInstrument after = mapDomain(() -> before.revoke(c.businessReason(), now));
        WalletAccount wallet = getWallet(before.accountId()).applyLedger(before.balanceType(), removed.currency(), removed.negate(), Money.zero(removed.currency()), Money.zero(removed.currency()), ledgerId(), now);
        repository.saveMutation(after, wallet, EventFactory.revoked(after, removed, delta(wallet, before.balanceType(), removed.currency(), removed.negate(), Money.zero(removed.currency()), Money.zero(removed.currency())), now), null, null); return after;
    }
    @Transactional public ReverseResult reverse(String id, ReverseRedemptionCommand c) {
        Instant now = clock.instant(); PromotionInstrument before = getBenefit(id); BenefitRedemption redemption = repository.findRedemption(c.redemptionId()).orElseThrow(() -> notFound("redemption not found"));
        if (c.amount().minorUnits() > redemption.unreversedMinorUnits()) throw new ApiException(ApiErrorCode.PRECONDITION_FAILED, "reversal exceeds unreversed amount");
        PromotionInstrument after = mapDomain(() -> before.reverse(c.amount(), c.businessReason(), now));
        WalletAccount wallet = getWallet(before.accountId()).applyLedger(before.balanceType(), c.amount().currency(), c.amount(), Money.zero(c.amount().currency()), c.amount().negate(), ledgerId(), now);
        ReversalRecord reversal = new ReversalRecord("brr-" + UUID.randomUUID(), c.redemptionId(), c.amount(), c.businessReason(), now);
        repository.saveMutation(after, wallet, EventFactory.reversed(after, c.redemptionId(), reversal.reversalId(), c.amount(), delta(wallet, before.balanceType(), c.amount().currency(), c.amount(), Money.zero(c.amount().currency()), c.amount().negate()), now), null, reversal);
        return new ReverseResult(after, c.redemptionId(), now);
    }
    @Scheduled(fixedDelayString = "${wallet-promotion.expiry-scan-ms:5000}") @Transactional public void expireDueBenefits() {
        Instant now = clock.instant(); for (PromotionInstrument before : repository.findExpirable(now, 50)) expireOne(before, now);
    }
    private void expireOne(PromotionInstrument before, Instant now) {
        BusinessReason reason = new BusinessReason(ReasonType.SYSTEM_EXPIRY, "VALIDITY_ELAPSED", "SCHEDULER_JOB", "wallet-promotion-expiry", null);
        Money available = before.availableAmount(); Money reserved = before.reservedAmount(); PromotionInstrument after = mapDomain(() -> before.expire(reason, now));
        WalletAccount wallet = getWallet(before.accountId()).applyLedger(before.balanceType(), available.currency(), available.negate(), reserved.negate(), Money.zero(available.currency()), ledgerId(), now);
        repository.saveMutation(after, wallet, EventFactory.expired(after, available.plus(reserved), delta(wallet, before.balanceType(), available.currency(), available.negate(), reserved.negate(), Money.zero(available.currency())), now), null, null);
    }
    private WalletAccount wallet(String accountId, String walletId, Instant now) { return repository.findWalletByAccountId(accountId).orElseGet(() -> WalletAccount.create(walletId, accountId, now)); }
    private static WalletBalanceDelta delta(WalletAccount wallet, BalanceType type, String currency, Money av, Money rv, Money rd) { WalletBalance b = wallet.balances().stream().filter(x -> x.balanceType()==type && x.currency().equals(currency)).findFirst().orElseThrow(); return new WalletBalanceDelta(wallet.walletAccountId(), type, av, rv, rd, b.availableBalance(), b.reservedBalance(), wallet.lastLedgerEntryId()); }
    private static String ledgerId() { return "wle-" + UUID.randomUUID(); }
    private static ApiException notFound(String m) { return new ApiException(ApiErrorCode.NOT_FOUND, m); }
    private static <T> T mapDomain(SupplierX<T> s) { try { return s.get(); } catch (DomainException e) { throw new ApiException(ApiErrorCode.DOMAIN_RULE_VIOLATION, e.getMessage()); } }
    @FunctionalInterface interface SupplierX<T> { T get(); }
    public record PagedBenefits(List<PromotionInstrument> items, int total, int limit, int offset) {}
    public record RedeemResult(PromotionInstrument benefit, BenefitRedemption redemption) {}
    public record ReverseResult(PromotionInstrument benefit, String reversedRedemptionId, Instant reversedAt) {}
}
