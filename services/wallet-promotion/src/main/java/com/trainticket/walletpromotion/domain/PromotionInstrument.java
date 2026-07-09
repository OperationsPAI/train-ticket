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
        if (issuanceSource == IssuanceSource.POST_SALES_COMP && (caseId == null || caseId.isBlank())) throw new DomainException("caseId is required for POST_SALES_COMP");
    }

    public static PromotionInstrument issue(String id, String accountId, String walletAccountId, BenefitType benefitType, BalanceType balanceType, Money amount, IssuanceSource source, String caseId, ApplicableScope scope, RedemptionRule redemptionRule, RevocationRule revocationRule, Instant validFrom, Instant validUntil, BusinessReason reason, Instant now) {
        amount.requirePositive("amount");
        return new PromotionInstrument(id, accountId, walletAccountId, benefitType, balanceType, PromotionStatus.ISSUED, amount, amount, Money.zero(amount.currency()), Money.zero(amount.currency()), source, source == IssuanceSource.POST_SALES_COMP ? caseId : null, scope, redemptionRule, revocationRule, validFrom, validUntil, reason, now, now, 1);
    }

    public PromotionInstrument reserve(Money amount, BusinessReason reason, Instant now) {
        requireNotExpired(now); amount.requirePositive("amount"); issuedAmount.requireSameCurrency(amount);
        if (!(status == PromotionStatus.ISSUED || status == PromotionStatus.RELEASED)) throw new DomainException("benefit cannot be reserved from " + status);
        if (availableAmount.minorUnits() < amount.minorUnits()) throw new DomainException("insufficient available benefit amount");
        return transition(PromotionStatus.RESERVED, availableAmount.minus(amount), reservedAmount.plus(amount), redeemedAmount, reason, now);
    }

    public PromotionInstrument redeem(Money amount, BusinessReason reason, Instant now, boolean fromReservation) {
        requireNotExpired(now); amount.requirePositive("amount"); issuedAmount.requireSameCurrency(amount);
        if (redemptionRule.maxRedemptionAmount() != null && amount.minorUnits() > redemptionRule.maxRedemptionAmount().minorUnits()) throw new DomainException("amount exceeds maxRedemptionAmount");
        if (redemptionRule.requiresReservation() && !fromReservation) throw new DomainException("reservation is required before redemption");
        if (fromReservation) {
            if (status != PromotionStatus.RESERVED) throw new DomainException("reserved redemption requires RESERVED status");
            if (reservedAmount.minorUnits() < amount.minorUnits()) throw new DomainException("insufficient reserved benefit amount");
            return transition(PromotionStatus.REDEEMED, availableAmount, reservedAmount.minus(amount), redeemedAmount.plus(amount), reason, now);
        }
        if (!(status == PromotionStatus.ISSUED || status == PromotionStatus.RELEASED)) throw new DomainException("benefit cannot be redeemed from " + status);
        if (availableAmount.minorUnits() < amount.minorUnits()) throw new DomainException("insufficient available benefit amount");
        PromotionStatus next = redemptionRule.singleUse() || availableAmount.minorUnits() == amount.minorUnits() ? PromotionStatus.REDEEMED : status;
        return transition(next, availableAmount.minus(amount), reservedAmount, redeemedAmount.plus(amount), reason, now);
    }

    public PromotionInstrument release(Money amount, BusinessReason reason, Instant now) {
        amount.requirePositive("amount"); issuedAmount.requireSameCurrency(amount);
        if (status != PromotionStatus.RESERVED) throw new DomainException("benefit cannot be released from " + status);
        if (reservedAmount.minorUnits() < amount.minorUnits()) throw new DomainException("insufficient reserved benefit amount");
        return transition(PromotionStatus.RELEASED, availableAmount.plus(amount), reservedAmount.minus(amount), redeemedAmount, reason, now);
    }

    public PromotionInstrument expire(BusinessReason reason, Instant now) {
        if (!(status == PromotionStatus.ISSUED || status == PromotionStatus.RESERVED || status == PromotionStatus.RELEASED)) throw new DomainException("benefit cannot expire from " + status);
        if (now.isBefore(validUntil)) throw new DomainException("benefit is not expired yet");
        return transition(PromotionStatus.EXPIRED, Money.zero(issuedAmount.currency()), Money.zero(issuedAmount.currency()), redeemedAmount, reason, now);
    }

    public PromotionInstrument revoke(BusinessReason reason, Instant now) {
        if (!(status == PromotionStatus.ISSUED || status == PromotionStatus.RELEASED)) throw new DomainException("benefit cannot be revoked from " + status);
        if (reservedAmount.minorUnits() != 0) throw new DomainException("reserved amount must be zero before revoke");
        return transition(PromotionStatus.REVOKED, Money.zero(issuedAmount.currency()), reservedAmount, redeemedAmount, reason, now);
    }

    public PromotionInstrument reverse(Money amount, BusinessReason reason, Instant now) {
        amount.requirePositive("amount"); issuedAmount.requireSameCurrency(amount);
        if (status != PromotionStatus.REDEEMED) throw new DomainException("benefit cannot reverse redemption from " + status);
        if (redeemedAmount.minorUnits() < amount.minorUnits()) throw new DomainException("reversal exceeds redeemed amount");
        return transition(PromotionStatus.REVERSED, availableAmount.plus(amount), reservedAmount, redeemedAmount.minus(amount), reason, now);
    }

    private PromotionInstrument transition(PromotionStatus next, Money available, Money reserved, Money redeemed, BusinessReason reason, Instant now) {
        if (next != status && !status.canTransitionTo(next)) throw new DomainException("invalid promotion transition " + status + " -> " + next);
        return new PromotionInstrument(benefitId, accountId, walletAccountId, benefitType, balanceType, next, issuedAmount, available, reserved, redeemed, issuanceSource, caseId, applicableScope, redemptionRule, revocationRule, validFrom, validUntil, reason, createdAt, now, version + 1);
    }
    private void requireNotExpired(Instant now) { if (!now.isBefore(validUntil)) throw new DomainException("benefit validity has expired"); }
    private static void requireText(String value, String name) { if (value == null || value.isBlank()) throw new DomainException(name + " is required"); }
}
