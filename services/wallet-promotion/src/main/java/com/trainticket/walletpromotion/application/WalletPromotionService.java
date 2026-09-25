package com.trainticket.walletpromotion.application;
import com.trainticket.walletpromotion.domain.IssuanceSource;

import com.trainticket.platformkit.http.ApiErrorCode;
import com.trainticket.platformkit.http.ApiException;
import com.trainticket.walletpromotion.domain.BalanceType;
import com.trainticket.walletpromotion.domain.BenefitRedemption;
import com.trainticket.walletpromotion.domain.BenefitReservation;
import com.trainticket.walletpromotion.domain.BenefitType;
import com.trainticket.walletpromotion.domain.BenefitUseReference;
import com.trainticket.walletpromotion.domain.BusinessReason;
import com.trainticket.walletpromotion.domain.DomainException;
import com.trainticket.walletpromotion.domain.Money;
import com.trainticket.walletpromotion.domain.PromotionInstrument;
import com.trainticket.walletpromotion.domain.PromotionStatus;
import com.trainticket.walletpromotion.domain.ReasonType;
import com.trainticket.walletpromotion.domain.ReservationRelease;
import com.trainticket.walletpromotion.domain.ReversalRecord;
import com.trainticket.walletpromotion.domain.WalletAccount;
import com.trainticket.walletpromotion.domain.WalletBalance;
import com.trainticket.walletpromotion.domain.WalletBalanceDelta;
import com.trainticket.walletpromotion.metrics.WalletPromotionMetrics;
import com.trainticket.walletpromotion.metrics.WalletPromotionMetrics.Operation;
import com.trainticket.walletpromotion.metrics.WalletPromotionMetrics.Outcome;
import com.trainticket.walletpromotion.metrics.WalletPromotionMetrics.ReasonCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class WalletPromotionService {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(WalletPromotionService.class);
    private final PromotionRepository repository;
    private final BenefitScopeVerifier scopeVerifier;
    private final Clock clock;
    private final WalletPromotionMetrics metrics;

    @org.springframework.beans.factory.annotation.Autowired
    public WalletPromotionService(PromotionRepository repository, BenefitScopeVerifier scopeVerifier, WalletPromotionMetrics metrics) {
        this(repository, scopeVerifier, Clock.systemUTC(), metrics);
    }

    /** Test convenience: same clock semantics, telemetry off. */
    WalletPromotionService(PromotionRepository repository, BenefitScopeVerifier scopeVerifier) {
        this(repository, scopeVerifier, Clock.systemUTC(), WalletPromotionMetrics.noop());
    }

    WalletPromotionService(PromotionRepository repository, BenefitScopeVerifier scopeVerifier, Clock clock) {
        this(repository, scopeVerifier, clock, WalletPromotionMetrics.noop());
    }

    WalletPromotionService(PromotionRepository repository, BenefitScopeVerifier scopeVerifier, Clock clock, WalletPromotionMetrics metrics) {
        this.repository = repository;
        this.scopeVerifier = scopeVerifier;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Transactional
    public PromotionInstrument issue(IssueBenefitCommand command) {
        try {
            return issueBenefit(command);
        } catch (RuntimeException exception) {
            recordRejected(Operation.ISSUE, command.businessReason(), command.amount());
            throw exception;
        }
    }

    private PromotionInstrument issueBenefit(IssueBenefitCommand command) {
        var existing = repository.findIssue(command);
        if (existing.isPresent()) {
            recordAfterCommit(Operation.ISSUE, Outcome.REPLAYED, command.businessReason(), command.amount());
            return existing.get();
        }
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
        repository.saveMutation(benefit, wallet, EventFactory.issued(benefit, delta, now), MutationArtifacts.none());
        repository.recordIssue(command, benefit.benefitId());
        recordAfterCommit(Operation.ISSUE, Outcome.SUCCESS, command.businessReason(), command.amount());
        return benefit;
    }

    public PromotionInstrument getBenefit(String id) {
        return repository.findBenefit(id).orElseThrow(() -> notFound("benefit not found"));
    }

    public PromotionInstrument getCompensationByCaseId(String caseId) {
        if (caseId == null || caseId.isBlank()) throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "caseId is required");
        return repository.findCompensationByCaseId(caseId).orElseThrow(() -> notFound("compensation benefit not found"));
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
        try {
            return reserveBenefit(id, command);
        } catch (RuntimeException exception) {
            recordRejected(Operation.RESERVE, command.businessReason(), command.amount());
            throw exception;
        }
    }

    private PromotionInstrument reserveBenefit(String id, ReserveBenefitCommand command) {
        Instant now = clock.instant();
        PromotionInstrument candidate = getBenefit(id);
        BenefitUseReference useReference = resolveUseReference(command.useReference(), command.reservationRef(), command.businessReason());
        requireText(command.reservationRef(), "reservationRef");
        Optional<BenefitReservation> prior = repository.findReservation(id, command.reservationRef());
        if (prior.isPresent()) {
            PromotionInstrument current = lockedBenefit(id);
            BenefitReservation persisted = repository.findReservation(id, command.reservationRef()).orElse(prior.get());
            PromotionInstrument replayed = replayedReservation(current, persisted, command, useReference);
            recordAfterCommit(Operation.RESERVE, Outcome.REPLAYED, command.businessReason(), command.amount());
            return replayed;
        }
        if (command.reservationExpiresAt() == null || !command.reservationExpiresAt().isAfter(now)) {
            throw new ApiException(ApiErrorCode.PRECONDITION_FAILED, "reservationExpiresAt must be in the future");
        }
        if (command.reservationExpiresAt().isAfter(candidate.validUntil())) {
            throw new ApiException(ApiErrorCode.PRECONDITION_FAILED, "reservation cannot outlive the benefit");
        }
        scopeVerifier.verify(candidate, useReference, command.businessReason());
        PromotionInstrument before = lockedBenefit(id);
        Optional<BenefitReservation> existing = repository.findReservation(id, command.reservationRef());
        if (existing.isPresent()) {
            PromotionInstrument replayed = replayedReservation(before, existing.get(), command, useReference);
            recordAfterCommit(Operation.RESERVE, Outcome.REPLAYED, command.businessReason(), command.amount());
            return replayed;
        }
        PromotionInstrument after = mapDomain(() -> before.reserve(command.amount(), command.businessReason(), now));
        BenefitReservation reservation = mapDomain(() -> BenefitReservation.reserve(
            command.reservationRef(), before, command.amount(), command.reservationExpiresAt(), useReference,
            command.businessReason(), now
        ));
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
        repository.saveMutation(after, wallet, EventFactory.reserved(after, command, reservation, delta, now), MutationArtifacts.reservation(reservation));
        recordAfterCommit(Operation.RESERVE, Outcome.SUCCESS, command.businessReason(), command.amount());
        return after;
    }

    @Transactional
    public RedeemResult redeem(String id, RedeemBenefitCommand command) {
        try {
            return redeemBenefit(id, command);
        } catch (RuntimeException exception) {
            recordRejected(Operation.REDEEM, command.businessReason(), command.amount());
            throw exception;
        }
    }

    private RedeemResult redeemBenefit(String id, RedeemBenefitCommand command) {
        Instant now = clock.instant();
        PromotionInstrument candidate = getBenefit(id);
        requireText(command.redemptionRef(), "redemptionRef");
        BenefitUseReference useReference = resolveUseReference(command.useReference(), command.redemptionRef(), command.businessReason());
        Optional<BenefitRedemption> prior = repository.findRedemptionByReference(id, command.redemptionRef());
        if (prior.isPresent()) {
            PromotionInstrument current = lockedBenefit(id);
            BenefitRedemption persisted = repository.findRedemptionByReference(id, command.redemptionRef()).orElse(prior.get());
            RedeemResult replayed = replayedRedemption(current, persisted, command, useReference);
            recordAfterCommit(Operation.REDEEM, Outcome.REPLAYED, command.businessReason(), command.amount());
            return replayed;
        }
        scopeVerifier.verify(candidate, useReference, command.businessReason());
        PromotionInstrument before = lockedBenefit(id);
        Optional<BenefitRedemption> existing = repository.findRedemptionByReference(id, command.redemptionRef());
        if (existing.isPresent()) {
            RedeemResult replayed = replayedRedemption(before, existing.get(), command, useReference);
            recordAfterCommit(Operation.REDEEM, Outcome.REPLAYED, command.businessReason(), command.amount());
            return replayed;
        }

        boolean fromReservation = command.reservationRef() != null && !command.reservationRef().isBlank();
        BenefitReservation reservation = null;
        if (fromReservation) {
            BenefitReservation current = repository.findReservation(id, command.reservationRef())
                .orElseThrow(() -> new ApiException(ApiErrorCode.PRECONDITION_FAILED, "reservation not found"));
            if (!current.useReference().equals(useReference)) {
                throw new ApiException(ApiErrorCode.PRECONDITION_FAILED, "redemption useReference differs from reservation");
            }
            reservation = mapDomain(() -> current.consume(command.amount(), now));
        }
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
            command.reservationRef(),
            useReference,
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
            MutationArtifacts.redemption(reservation, redemption)
        );
        recordAfterCommit(Operation.REDEEM, Outcome.SUCCESS, command.businessReason(), command.amount());
        return new RedeemResult(after, redemption);
    }

    @Transactional
    public PromotionInstrument release(String id, ReleaseBenefitCommand command) {
        try {
            return releaseBenefit(id, command);
        } catch (RuntimeException exception) {
            recordRejected(Operation.RELEASE, command.businessReason(), command.amount());
            throw exception;
        }
    }

    private PromotionInstrument releaseBenefit(String id, ReleaseBenefitCommand command) {
        Instant now = clock.instant();
        PromotionInstrument before = lockedBenefit(id);
        requireText(command.reservationRef(), "reservationRef");
        String releaseRef = operationRef(command.releaseRef(), command.businessReason());
        Optional<ReservationRelease> existing = repository.findReleaseByReference(id, releaseRef);
        if (existing.isPresent()) {
            if (existing.get().sameRequest(command.reservationRef(), command.amount(), command.businessReason())) {
                recordAfterCommit(Operation.RELEASE, Outcome.REPLAYED, command.businessReason(), command.amount());
                return before;
            }
            throw new ApiException(ApiErrorCode.CONFLICT, "releaseRef already has another release request");
        }
        BenefitReservation current = repository.findReservation(id, command.reservationRef())
            .orElseThrow(() -> new ApiException(ApiErrorCode.PRECONDITION_FAILED, "reservation not found"));
        BenefitReservation reservation = mapDomain(() -> current.release(command.amount(), now));
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
        ReservationRelease release = new ReservationRelease(
            "brl-" + UUID.randomUUID(), id, command.reservationRef(), releaseRef, command.amount(), command.businessReason(), now
        );
        repository.saveMutation(after, wallet, EventFactory.released(after, command, release, delta, now), MutationArtifacts.release(reservation, release));
        recordAfterCommit(Operation.RELEASE, Outcome.SUCCESS, command.businessReason(), command.amount());
        return after;
    }

    @Transactional
    public PromotionInstrument revoke(String id, RevokeBenefitCommand command) {
        try {
            return revokeBenefit(id, command);
        } catch (RuntimeException exception) {
            // The command carries no amount, so the currency is only knowable from
            // the stored benefit; when even that read fails there is no valid point.
            recordRejected(Operation.REVOKE, command.businessReason(), repository.findBenefit(id).map(PromotionInstrument::issuedAmount).orElse(null));
            throw exception;
        }
    }

    private PromotionInstrument revokeBenefit(String id, RevokeBenefitCommand command) {
        Instant now = command.effectiveAt() == null ? clock.instant() : command.effectiveAt();
        PromotionInstrument before = lockedBenefit(id);
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
        repository.saveMutation(after, wallet, EventFactory.revoked(after, removed, delta, now), MutationArtifacts.none());
        recordAfterCommit(Operation.REVOKE, Outcome.SUCCESS, command.businessReason(), removed);
        return after;
    }

    @Transactional
    public ReverseResult reverse(String id, ReverseRedemptionCommand command) {
        try {
            return reverseRedemption(id, command);
        } catch (RuntimeException exception) {
            recordRejected(Operation.REVERSE, command.businessReason(), command.amount());
            throw exception;
        }
    }

    private ReverseResult reverseRedemption(String id, ReverseRedemptionCommand command) {
        Instant now = clock.instant();
        PromotionInstrument before = lockedBenefit(id);
        BenefitRedemption redemption = repository.findRedemption(command.redemptionId())
            .orElseThrow(() -> notFound("redemption not found"));
        if (!redemption.benefitId().equals(id) || !redemption.accountId().equals(before.accountId())) {
            throw new ApiException(ApiErrorCode.PRECONDITION_FAILED, "redemption does not belong to the benefit");
        }
        String reversalRef = operationRef(command.reversalRef(), command.businessReason());
        Optional<ReversalRecord> existing = repository.findReversalByReference(command.redemptionId(), reversalRef);
        if (existing.isPresent()) {
            if (existing.get().sameRequest(command.amount(), command.businessReason())) {
                recordAfterCommit(Operation.REVERSE, Outcome.REPLAYED, command.businessReason(), command.amount());
                return new ReverseResult(before, command.redemptionId(), existing.get().reversedAt());
            }
            throw new ApiException(ApiErrorCode.CONFLICT, "reversalRef already has another reversal request");
        }
        if (command.amount().minorUnits() > redemption.unreversedMinorUnits()) {
            throw new ApiException(ApiErrorCode.PRECONDITION_FAILED, "reversal exceeds unreversed amount");
        }
        PromotionInstrument after = mapDomain(() -> before.reverse(command.amount(), command.businessReason(), now));
        Money zero = Money.zero(command.amount().currency());
        Money availableDelta = new Money(
            command.amount().currency(),
            after.availableAmount().minorUnits() - before.availableAmount().minorUnits()
        );
        Money reservedDelta = new Money(
            command.amount().currency(),
            after.reservedAmount().minorUnits() - before.reservedAmount().minorUnits()
        );
        WalletAccount wallet = getWallet(before.accountId()).applyLedger(
            before.balanceType(),
            command.amount().currency(),
            availableDelta,
            reservedDelta,
            command.amount().negate(),
            ledgerId(),
            now
        );
        ReversalRecord reversal = new ReversalRecord(
            "brr-" + UUID.randomUUID(),
            command.redemptionId(),
            reversalRef,
            command.amount(),
            command.businessReason(),
            now
        );
        WalletBalanceDelta delta = delta(
            wallet,
            before.balanceType(),
            command.amount().currency(),
            availableDelta,
            reservedDelta,
            command.amount().negate()
        );
        repository.saveMutation(
            after,
            wallet,
            EventFactory.reversed(after, command.redemptionId(), reversal.reversalId(), reversalRef, command.amount(), delta, now),
            MutationArtifacts.reversal(reversal)
        );
        recordAfterCommit(Operation.REVERSE, Outcome.SUCCESS, command.businessReason(), command.amount());
        return new ReverseResult(after, command.redemptionId(), now);
    }

    @Scheduled(fixedDelayString = "${wallet-promotion.expiry-scan-ms:5000}")
    @Transactional
    public void expireDueBenefits() {
        Instant now = clock.instant();
        for (PromotionInstrument before : repository.findExpirable(now, 50)) {
            try {
                expireOne(before, now);
            } catch (RuntimeException ex) {
                // One bad candidate must not abort the whole sweep.
                LOGGER.warn("expiry sweep skipped benefitId={} exceptionClass={} message={}",
                    before.benefitId(), ex.getClass().getName(), ex.getMessage());
                recordRejected(Operation.EXPIRE, null, before.issuedAmount());
            }
        }
        for (BenefitReservation reservation : repository.findExpiredReservations(now, 50)) {
            try {
                expireReservation(reservation, now);
            } catch (RuntimeException ex) {
                LOGGER.warn("reservation expiry skipped benefitId={} reservationRef={} exceptionClass={} message={}",
                    reservation.benefitId(), reservation.reservationRef(), ex.getClass().getName(), ex.getMessage());
                recordRejected(Operation.RELEASE, null, reservation.originalAmount());
            }
        }
    }

    private void expireOne(PromotionInstrument candidate, Instant now) {
        PromotionInstrument before = lockedBenefit(candidate.benefitId());
        if (before.status().terminal() || now.isBefore(before.validUntil())) return;
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
        List<BenefitReservation> expiredReservations = repository.findActiveReservations(before.benefitId()).stream()
            .map(reservation -> reservation.expireWithBenefit(now))
            .toList();
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
        repository.saveMutation(after, wallet, EventFactory.expired(after, available.plus(reserved), delta, now), MutationArtifacts.reservations(expiredReservations));
        recordAfterCommit(Operation.EXPIRE, Outcome.SUCCESS, reason, available.plus(reserved));
    }

    private void expireReservation(BenefitReservation candidate, Instant now) {
        PromotionInstrument before = lockedBenefit(candidate.benefitId());
        BenefitReservation current = repository.findReservation(candidate.benefitId(), candidate.reservationRef())
            .orElseThrow(() -> notFound("reservation not found"));
        if (current.status() != com.trainticket.walletpromotion.domain.BenefitReservationStatus.ACTIVE
            || now.isBefore(current.expiresAt())) return;
        if (before.status().terminal()) return;
        Money amount = current.remainingAmount();
        BusinessReason reason = new BusinessReason(
            ReasonType.SYSTEM_EXPIRY, "RESERVATION_VALIDITY_ELAPSED", "ORDER", current.useReference().orderId(), null
        );
        PromotionInstrument after = mapDomain(() -> before.release(amount, reason, now));
        BenefitReservation expired = mapDomain(() -> current.expire(now));
        Money zero = Money.zero(amount.currency());
        WalletAccount wallet = getWallet(before.accountId()).applyLedger(
            before.balanceType(), amount.currency(), amount, amount.negate(), zero, ledgerId(), now
        );
        WalletBalanceDelta delta = delta(wallet, before.balanceType(), amount.currency(), amount, amount.negate(), zero);
        String releaseRef = "reservation-expiry:" + current.reservationRef();
        ReleaseBenefitCommand command = new ReleaseBenefitCommand(amount, current.reservationRef(), releaseRef, reason);
        ReservationRelease release = new ReservationRelease(
            "brl-" + UUID.randomUUID(), before.benefitId(), current.reservationRef(), releaseRef, amount, reason, now
        );
        repository.saveMutation(after, wallet, EventFactory.released(after, command, release, delta, now), MutationArtifacts.release(expired, release));
        recordAfterCommit(Operation.RELEASE, Outcome.SUCCESS, reason, amount);
    }

    private WalletAccount wallet(String accountId, String walletId, Instant now) {
        return repository.findWalletByAccountId(accountId).orElseGet(() -> WalletAccount.create(walletId, accountId, now));
    }

    private PromotionInstrument lockedBenefit(String benefitId) {
        return repository.lockBenefit(benefitId).orElseThrow(() -> notFound("benefit not found"));
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

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new ApiException(ApiErrorCode.VALIDATION_FAILED, name + " is required");
        return value;
    }

    private static String operationRef(String explicit, BusinessReason reason) {
        if (explicit != null && !explicit.isBlank()) return explicit.trim();
        if (reason == null) throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "businessReason is required");
        return reason.key();
    }

    private static PromotionInstrument replayedReservation(
        PromotionInstrument benefit,
        BenefitReservation reservation,
        ReserveBenefitCommand command,
        BenefitUseReference useReference
    ) {
        if (reservation.sameRequest(command.amount(), command.reservationExpiresAt(), useReference, command.businessReason())) {
            return benefit;
        }
        throw new ApiException(ApiErrorCode.CONFLICT, "reservationRef already has another reservation request");
    }

    private static RedeemResult replayedRedemption(
        PromotionInstrument benefit,
        BenefitRedemption redemption,
        RedeemBenefitCommand command,
        BenefitUseReference useReference
    ) {
        if (redemption.sameRequest(
            command.amount(), command.redemptionRef(), command.reservationRef(), useReference, command.businessReason()
        )) {
            return new RedeemResult(benefit, redemption);
        }
        throw new ApiException(ApiErrorCode.CONFLICT, "redemptionRef already has another redemption request");
    }

    private static BenefitUseReference resolveUseReference(
        BenefitUseReference explicit,
        String operationReference,
        BusinessReason reason
    ) {
        if (explicit != null) return explicit;
        if (reason != null
            && "ORDER".equals(reason.referenceType())
            && reason.referenceId() != null
            && reason.referenceId().equals(operationReference)) {
            return new BenefitUseReference(reason.referenceId(), null, List.of());
        }
        throw new ApiException(
            ApiErrorCode.PRECONDITION_FAILED,
            "useReference is required unless the operation and businessReason identify the same order"
        );
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

    /**
     * Records a terminal outcome once the surrounding transaction commits.
     *
     * <p>Success must not be counted before the write is durable: a rollback
     * after this point would otherwise report an operation that never happened.
     */
    private void recordAfterCommit(Operation operation, Outcome outcome, BusinessReason reason, Money amount) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    record(operation, outcome, reason, amount);
                }
            });
            return;
        }
        record(operation, outcome, reason, amount);
    }

    /**
     * Records a rejected attempt immediately. Rejections roll the transaction
     * back, so {@code afterCommit} would never fire for them, yet the attempt is
     * exactly what an operator needs to see.
     */
    private void recordRejected(Operation operation, BusinessReason reason, Money amount) {
        record(operation, Outcome.REJECTED, reason, amount);
    }

    private void record(Operation operation, Outcome outcome, BusinessReason reason, Money amount) {
        String currency = amount == null ? null : amount.currency();
        if (currency == null || currency.isBlank()) {
            // Integer minor units always carry a currency; without one there is no
            // valid point to record.
            return;
        }
        try {
            metrics.operation(operation, outcome, ReasonCode.from(reason), currency);
        } catch (RuntimeException ignored) {
            // Telemetry is diagnostic and must never fail or mask a business operation.
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
