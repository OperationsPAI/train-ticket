package com.trainticket.walletpromotion.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PromotionInstrument(
    String benefitId,
    String accountId,
    String walletAccountId,
    BenefitType benefitType,
    BalanceType balanceType,
    PromotionStatus status,
    Money issuedAmount,
    Money availableAmount,
    Money reservedAmount,
    Money redeemedAmount,
    IssuanceSource issuanceSource,
    String caseId,
    ApplicableScope applicableScope,
    RedemptionRule redemptionRule,
    RevocationRule revocationRule,
    Instant validFrom,
    Instant validUntil,
    BusinessReason businessReason,
    Instant createdAt,
    Instant updatedAt,
    long version
) {
    public PromotionInstrument {
        requireText(benefitId, "benefitId"); requireText(accountId, "accountId"); requireText(walletAccountId, "walletAccountId");
        Objects.requireNonNull(benefitType); Objects.requireNonNull(balanceType); Objects.requireNonNull(status);
        issuedAmount.requirePositive("issuedAmount"); availableAmount.requireNonNegative("availableAmount"); reservedAmount.requireNonNegative("reservedAmount"); redeemedAmount.requireNonNegative("redeemedAmount");
        issuedAmount.requireSameCurrency(availableAmount); issuedAmount.requireSameCurrency(reservedAmount); issuedAmount.requireSameCurrency(redeemedAmount);
        Objects.requireNonNull(issuanceSource); Objects.requireNonNull(applicableScope); Objects.requireNonNull(redemptionRule); Objects.requireNonNull(revocationRule);
        Objects.requireNonNull(validFrom); Objects.requireNonNull(validUntil); Objects.requireNonNull(businessReason); Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
        if (!validUntil.isAfter(validFrom)) throw new DomainException("validUntil must be after validFrom");
        if (!issuedAmount.currency().equals(applicableScope.currency())) throw new DomainException("scope currency must match issuedAmount");
        long accounted = Math.addExact(Math.addExact(availableAmount.minorUnits(), reservedAmount.minorUnits()), redeemedAmount.minorUnits());
        if (accounted > issuedAmount.minorUnits()) throw new DomainException("benefit balances exceed issuedAmount");
        if (issuanceSource != IssuanceSource.MANUAL_OPS && (caseId == null || caseId.isBlank())) {
            throw new DomainException("caseId is required for compensation issuance");
        }
    }

    public static PromotionInstrument issue(String id, String accountId, String walletAccountId, BenefitType benefitType, BalanceType balanceType, Money amount, IssuanceSource source, String caseId, ApplicableScope scope, RedemptionRule redemptionRule, RevocationRule revocationRule, Instant validFrom, Instant validUntil, BusinessReason reason, Instant now) {
        amount.requirePositive("amount");
        return new PromotionInstrument(id, accountId, walletAccountId, benefitType, balanceType, PromotionStatus.ISSUED, amount, amount, Money.zero(amount.currency()), Money.zero(amount.currency()), source, source == IssuanceSource.MANUAL_OPS ? null : caseId, scope, redemptionRule, revocationRule, validFrom, validUntil, reason, now, now, 1);
    }

    public PromotionInstrument reserve(Money amount, BusinessReason reason, Instant now) {
        requireUsable(now); amount.requirePositive("amount"); issuedAmount.requireSameCurrency(amount);
        requireActive("reserved");
        if (availableAmount.minorUnits() < amount.minorUnits()) throw new DomainException("insufficient available benefit amount");
        if (redemptionRule.singleUse() && (reservedAmount.minorUnits() != 0 || redeemedAmount.minorUnits() != 0
            || availableAmount.minorUnits() != amount.minorUnits())) {
            throw new DomainException("single-use benefit must reserve its complete remaining amount");
        }
        return transition(PromotionStatus.RESERVED, availableAmount.minus(amount), reservedAmount.plus(amount), redeemedAmount, reason, now);
    }

    public PromotionInstrument redeem(Money amount, BusinessReason reason, Instant now, boolean fromReservation) {
        requireUsable(now); amount.requirePositive("amount"); issuedAmount.requireSameCurrency(amount);
        if (redemptionRule.maxRedemptionAmount() != null && amount.minorUnits() > redemptionRule.maxRedemptionAmount().minorUnits()) throw new DomainException("amount exceeds maxRedemptionAmount");
        if (redemptionRule.requiresReservation() && !fromReservation) throw new DomainException("reservation is required before redemption");
        requireActive("redeemed");
        if (fromReservation) {
            if (reservedAmount.minorUnits() < amount.minorUnits()) throw new DomainException("insufficient reserved benefit amount");
            if (redemptionRule.singleUse() && (reservedAmount.minorUnits() != amount.minorUnits() || availableAmount.minorUnits() != 0)) {
                throw new DomainException("single-use benefit must redeem its complete reserved amount");
            }
            Money reserved = reservedAmount.minus(amount);
            Money redeemed = redeemedAmount.plus(amount);
            return transition(redemptionStatus(availableAmount, reserved), availableAmount, reserved, redeemed, reason, now);
        }
        if (availableAmount.minorUnits() < amount.minorUnits()) throw new DomainException("insufficient available benefit amount");
        if (redemptionRule.singleUse() && (availableAmount.minorUnits() != amount.minorUnits() || reservedAmount.minorUnits() != 0)) {
            throw new DomainException("single-use benefit must redeem its complete available amount");
        }
        Money available = availableAmount.minus(amount);
        Money redeemed = redeemedAmount.plus(amount);
        return transition(redemptionStatus(available, reservedAmount), available, reservedAmount, redeemed, reason, now);
    }

    public PromotionInstrument release(Money amount, BusinessReason reason, Instant now) {
        requireUsable(now);
        amount.requirePositive("amount"); issuedAmount.requireSameCurrency(amount);
        requireActive("released");
        if (reservedAmount.minorUnits() < amount.minorUnits()) throw new DomainException("insufficient reserved benefit amount");
        Money reserved = reservedAmount.minus(amount);
        PromotionStatus next = reserved.minorUnits() > 0 ? PromotionStatus.RESERVED : PromotionStatus.RELEASED;
        return transition(next, availableAmount.plus(amount), reserved, redeemedAmount, reason, now);
    }

    public PromotionInstrument expire(BusinessReason reason, Instant now) {
        requireActive("expired");
        if (now.isBefore(validUntil)) throw new DomainException("benefit is not expired yet");
        return transition(PromotionStatus.EXPIRED, Money.zero(issuedAmount.currency()), Money.zero(issuedAmount.currency()), redeemedAmount, reason, now);
    }

    public PromotionInstrument revoke(BusinessReason reason, Instant now) {
        requireActive("revoked");
        if (reservedAmount.minorUnits() != 0) throw new DomainException("reserved amount must be zero before revoke");
        return transition(PromotionStatus.REVOKED, Money.zero(issuedAmount.currency()), reservedAmount, redeemedAmount, reason, now);
    }

    public PromotionInstrument reverse(Money amount, BusinessReason reason, Instant now) {
        amount.requirePositive("amount"); issuedAmount.requireSameCurrency(amount);
        if (redeemedAmount.minorUnits() < amount.minorUnits()) throw new DomainException("reversal exceeds redeemed amount");
        boolean restoreAvailable = status != PromotionStatus.EXPIRED
            && status != PromotionStatus.REVOKED
            && now.isBefore(validUntil);
        PromotionStatus next = restoreAvailable
            ? PromotionStatus.REVERSED
            : status == PromotionStatus.REVOKED ? PromotionStatus.REVOKED : PromotionStatus.EXPIRED;
        Money available = restoreAvailable ? availableAmount.plus(amount) : Money.zero(issuedAmount.currency());
        Money reserved = restoreAvailable ? reservedAmount : Money.zero(issuedAmount.currency());
        return transition(next, available, reserved, redeemedAmount.minus(amount), reason, now);
    }

    private PromotionInstrument transition(PromotionStatus next, Money available, Money reserved, Money redeemed, BusinessReason reason, Instant now) {
        return new PromotionInstrument(benefitId, accountId, walletAccountId, benefitType, balanceType, next, issuedAmount, available, reserved, redeemed, issuanceSource, caseId, applicableScope, redemptionRule, revocationRule, validFrom, validUntil, reason, createdAt, now, version + 1);
    }
    private void requireActive(String operation) { if (status.terminal()) throw new DomainException("benefit cannot be " + operation + " from " + status); }
    private void requireUsable(Instant now) {
        if (now.isBefore(validFrom)) throw new DomainException("benefit validity has not started");
        if (!now.isBefore(validUntil)) throw new DomainException("benefit validity has expired");
    }
    private static PromotionStatus redemptionStatus(Money available, Money reserved) {
        if (reserved.minorUnits() > 0) return PromotionStatus.RESERVED;
        if (available.minorUnits() > 0) return PromotionStatus.ISSUED;
        return PromotionStatus.REDEEMED;
    }
    private static void requireText(String value, String name) { if (value == null || value.isBlank()) throw new DomainException(name + " is required"); }
}
