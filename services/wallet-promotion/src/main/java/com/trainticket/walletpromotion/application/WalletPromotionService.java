package com.trainticket.walletpromotion.application;

import com.trainticket.platformkit.http.ApiErrorCode;
import com.trainticket.platformkit.http.ApiException;
import com.trainticket.walletpromotion.domain.BalanceType;
import com.trainticket.walletpromotion.domain.BenefitRedemption;
import com.trainticket.walletpromotion.domain.BenefitType;
import com.trainticket.walletpromotion.domain.BusinessReason;
import com.trainticket.walletpromotion.domain.DomainException;
import com.trainticket.walletpromotion.domain.Money;
import com.trainticket.walletpromotion.domain.PromotionInstrument;
import com.trainticket.walletpromotion.domain.PromotionStatus;
import com.trainticket.walletpromotion.domain.ReasonType;
import com.trainticket.walletpromotion.domain.ReversalRecord;
import com.trainticket.walletpromotion.domain.WalletAccount;
import com.trainticket.walletpromotion.domain.WalletBalance;
import com.trainticket.walletpromotion.domain.WalletBalanceDelta;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletPromotionService {
    private final PromotionRepository repository;
    private final Clock clock;

    public WalletPromotionService(PromotionRepository repository) {
        this(repository, Clock.systemUTC());
    }

    WalletPromotionService(PromotionRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public PromotionInstrument issue(IssueBenefitCommand command) {
        Instant now = clock.instant();
        String walletAccountId = repository.findWalletByAccountId(command.accountId())
            .map(WalletAccount::walletAccountId)
            .orElseGet(() -> "wac-" + UUID.randomUUID());
        PromotionInstrument benefit = PromotionInstrument.issue(
            "ben-" + UUID.randomUUID(),
            command.accountId(),
            walletAccountId,
            command.benefitType(),
            command.balanceType(),
            command.amount(),
            command.issuanceSource(),
            command.caseId(),
            command.applicableScope(),
            command.redemptionRule(),
            command.revocationRule(),
            command.validFrom(),
            command.validUntil(),
            command.businessReason(),
            now
        );
        Money zero = Money.zero(command.amount().currency());
        WalletAccount wallet = wallet(command.accountId(), walletAccountId, now).applyLedger(
            command.balanceType(),
            command.amount().currency(),
            command.amount(),
            zero,
            zero,
            ledgerId(),
            now
        );
        WalletBalanceDelta delta = delta(
            wallet,
            command.balanceType(),
            command.amount().currency(),
            command.amount(),
            zero,
            zero
        );
        repository.saveMutation(benefit, wallet, EventFactory.issued(benefit, delta, now), null, null);
        return benefit;
    }

    public PromotionInstrument getBenefit(String id) {
        return repository.findBenefit(id).orElseThrow(() -> notFound("benefit not found"));
    }

    public WalletAccount getWallet(String accountId) {
        return repository.findWalletByAccountId(accountId).orElseThrow(() -> notFound("wallet account not found"));
    }

    public PagedBenefits list(String accountId, PromotionStatus status, BenefitType type, int limit, int offset) {
        if (accountId == null || accountId.isBlank()) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "byAccountId is required");
        }
        int boundedLimit = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 100));
        int safeOffset = Math.max(0, offset);
        return new PagedBenefits(
            repository.listBenefits(accountId, status, type, boundedLimit, safeOffset),
            repository.countBenefits(accountId, status, type),
            boundedLimit,
            safeOffset
        );
    }

    @Transactional
    public PromotionInstrument reserve(String id, ReserveBenefitCommand command) {
        Instant now = clock.instant();
        PromotionInstrument before = getBenefit(id);
        PromotionInstrument after = mapDomain(() -> before.reserve(command.amount(), command.businessReason(), now));
        Money availableDelta = command.amount().negate();
        Money redeemedDelta = Money.zero(command.amount().currency());
        WalletAccount wallet = getWallet(before.accountId()).applyLedger(
            before.balanceType(),
            command.amount().currency(),
            availableDelta,
            command.amount(),
            redeemedDelta,
            ledgerId(),
            now
        );
        WalletBalanceDelta delta = delta(
            wallet,
            before.balanceType(),
            command.amount().currency(),
            availableDelta,
            command.amount(),
            redeemedDelta
        );
        repository.saveMutation(after, wallet, EventFactory.reserved(after, command, delta, now), null, null);
        return after;
    }

    @Transactional
    public RedeemResult redeem(String id, RedeemBenefitCommand command) {
        Instant now = clock.instant();
        PromotionInstrument before = getBenefit(id);
        Optional<BenefitRedemption> existing = repository.findRedemptionByReason(id, command.businessReason());
        if (existing.isPresent()) {
            BenefitRedemption redemption = existing.get();
            if (redemption.amount().equals(command.amount()) && redemption.redemptionRef().equals(command.redemptionRef())) {
                return new RedeemResult(before, redemption);
            }
            throw new ApiException(ApiErrorCode.CONFLICT, "benefit already redeemed for businessReason");
        }

        boolean fromReservation = command.reservationRef() != null && !command.reservationRef().isBlank();
        PromotionInstrument after = mapDomain(
            () -> before.redeem(command.amount(), command.businessReason(), now, fromReservation)
        );
        Money availableDelta = fromReservation ? Money.zero(command.amount().currency()) : command.amount().negate();
        Money reservedDelta = fromReservation ? command.amount().negate() : Money.zero(command.amount().currency());
        WalletAccount wallet = getWallet(before.accountId()).applyLedger(
            before.balanceType(),
            command.amount().currency(),
            availableDelta,
            reservedDelta,
            command.amount(),
            ledgerId(),
            now
        );
        BenefitRedemption redemption = new BenefitRedemption(
            "brd-" + UUID.randomUUID(),
            id,
            before.accountId(),
            command.amount(),
            command.redemptionRef(),
            command.businessReason(),
            now,
            0
        );
        WalletBalanceDelta delta = delta(
            wallet,
            before.balanceType(),
            command.amount().currency(),
            availableDelta,
            reservedDelta,
            command.amount()
        );
        repository.saveMutation(
            after,
            wallet,
            EventFactory.redeemed(after, redemption, command.reservationRef(), delta, now),
            redemption,
            null
        );
        return new RedeemResult(after, redemption);
    }

    @Transactional
    public PromotionInstrument release(String id, ReleaseBenefitCommand command) {
        Instant now = clock.instant();
        PromotionInstrument before = getBenefit(id);
        PromotionInstrument after = mapDomain(() -> before.release(command.amount(), command.businessReason(), now));
        Money reservedDelta = command.amount().negate();
        Money redeemedDelta = Money.zero(command.amount().currency());
        WalletAccount wallet = getWallet(before.accountId()).applyLedger(
            before.balanceType(),
            command.amount().currency(),
            command.amount(),
            reservedDelta,
            redeemedDelta,
            ledgerId(),
            now
        );
        WalletBalanceDelta delta = delta(
            wallet,
            before.balanceType(),
            command.amount().currency(),
            command.amount(),
            reservedDelta,
            redeemedDelta
        );
        repository.saveMutation(after, wallet, EventFactory.released(after, command, delta, now), null, null);
        return after;
    }

    @Transactional
    public PromotionInstrument revoke(String id, RevokeBenefitCommand command) {
        Instant now = command.effectiveAt() == null ? clock.instant() : command.effectiveAt();
        PromotionInstrument before = getBenefit(id);
        Money removed = before.availableAmount();
        Money zero = Money.zero(removed.currency());
        PromotionInstrument after = mapDomain(() -> before.revoke(command.businessReason(), now));
        WalletAccount wallet = getWallet(before.accountId()).applyLedger(
            before.balanceType(),
            removed.currency(),
            removed.negate(),
            zero,
            zero,
            ledgerId(),
            now
        );
        WalletBalanceDelta delta = delta(wallet, before.balanceType(), removed.currency(), removed.negate(), zero, zero);
        repository.saveMutation(after, wallet, EventFactory.revoked(after, removed, delta, now), null, null);
        return after;
    }

    @Transactional
    public ReverseResult reverse(String id, ReverseRedemptionCommand command) {
        Instant now = clock.instant();
        PromotionInstrument before = getBenefit(id);
        BenefitRedemption redemption = repository.findRedemption(command.redemptionId())
            .orElseThrow(() -> notFound("redemption not found"));
        if (command.amount().minorUnits() > redemption.unreversedMinorUnits()) {
            throw new ApiException(ApiErrorCode.PRECONDITION_FAILED, "reversal exceeds unreversed amount");
        }
        PromotionInstrument after = mapDomain(() -> before.reverse(command.amount(), command.businessReason(), now));
        Money zero = Money.zero(command.amount().currency());
        WalletAccount wallet = getWallet(before.accountId()).applyLedger(
            before.balanceType(),
            command.amount().currency(),
            command.amount(),
            zero,
            command.amount().negate(),
            ledgerId(),
            now
        );
        ReversalRecord reversal = new ReversalRecord(
            "brr-" + UUID.randomUUID(),
            command.redemptionId(),
            command.amount(),
            command.businessReason(),
            now
        );
        WalletBalanceDelta delta = delta(
            wallet,
            before.balanceType(),
            command.amount().currency(),
            command.amount(),
            zero,
            command.amount().negate()
        );
        repository.saveMutation(
            after,
            wallet,
            EventFactory.reversed(after, command.redemptionId(), reversal.reversalId(), command.amount(), delta, now),
            null,
            reversal
        );
        return new ReverseResult(after, command.redemptionId(), now);
    }

    @Scheduled(fixedDelayString = "${wallet-promotion.expiry-scan-ms:5000}")
    @Transactional
    public void expireDueBenefits() {
        Instant now = clock.instant();
        for (PromotionInstrument before : repository.findExpirable(now, 50)) {
            expireOne(before, now);
        }
    }

    private void expireOne(PromotionInstrument before, Instant now) {
        BusinessReason reason = new BusinessReason(
            ReasonType.SYSTEM_EXPIRY,
            "VALIDITY_ELAPSED",
            "SCHEDULER_JOB",
            "wallet-promotion-expiry",
            null
        );
        Money available = before.availableAmount();
        Money reserved = before.reservedAmount();
        Money zero = Money.zero(available.currency());
        PromotionInstrument after = mapDomain(() -> before.expire(reason, now));
        WalletAccount wallet = getWallet(before.accountId()).applyLedger(
            before.balanceType(),
            available.currency(),
            available.negate(),
            reserved.negate(),
            zero,
            ledgerId(),
            now
        );
        WalletBalanceDelta delta = delta(
            wallet,
            before.balanceType(),
            available.currency(),
            available.negate(),
            reserved.negate(),
            zero
        );
        repository.saveMutation(after, wallet, EventFactory.expired(after, available.plus(reserved), delta, now), null, null);
    }

    private WalletAccount wallet(String accountId, String walletId, Instant now) {
        return repository.findWalletByAccountId(accountId).orElseGet(() -> WalletAccount.create(walletId, accountId, now));
    }

    private static WalletBalanceDelta delta(
        WalletAccount wallet,
        BalanceType balanceType,
        String currency,
        Money availableDelta,
        Money reservedDelta,
        Money redeemedDelta
    ) {
        WalletBalance balance = wallet.balances().stream()
            .filter(candidate -> candidate.balanceType() == balanceType && candidate.currency().equals(currency))
            .findFirst()
            .orElseThrow();
        return new WalletBalanceDelta(
            wallet.walletAccountId(),
            balanceType,
            availableDelta,
            reservedDelta,
            redeemedDelta,
            balance.availableBalance(),
            balance.reservedBalance(),
            wallet.lastLedgerEntryId()
        );
    }

    private static String ledgerId() {
        return "wle-" + UUID.randomUUID();
    }

    private static ApiException notFound(String message) {
        return new ApiException(ApiErrorCode.NOT_FOUND, message);
    }

    private static <T> T mapDomain(DomainOperation<T> operation) {
        try {
            return operation.get();
        } catch (DomainException exception) {
            throw new ApiException(ApiErrorCode.DOMAIN_RULE_VIOLATION, exception.getMessage());
        }
    }

    @FunctionalInterface
    interface DomainOperation<T> {
        T get();
    }

    public record PagedBenefits(List<PromotionInstrument> items, int total, int limit, int offset) {
    }

    public record RedeemResult(PromotionInstrument benefit, BenefitRedemption redemption) {
    }

    public record ReverseResult(PromotionInstrument benefit, String reversedRedemptionId, Instant reversedAt) {
    }
}
