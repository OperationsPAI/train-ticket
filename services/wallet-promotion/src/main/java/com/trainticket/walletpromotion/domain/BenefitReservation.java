package com.trainticket.walletpromotion.domain;

import java.time.Instant;
import java.util.Objects;

public record BenefitReservation(
    String reservationRef,
    String benefitId,
    String accountId,
    Money originalAmount,
    Money remainingAmount,
    Instant expiresAt,
    BenefitUseReference useReference,
    BusinessReason businessReason,
    BenefitReservationStatus status,
    Instant reservedAt,
    Instant updatedAt
) {
    public BenefitReservation {
        requireText(reservationRef, "reservationRef");
        requireText(benefitId, "benefitId");
        requireText(accountId, "accountId");
        originalAmount.requirePositive("originalAmount");
        remainingAmount.requireNonNegative("remainingAmount");
        originalAmount.requireSameCurrency(remainingAmount);
        if (remainingAmount.minorUnits() > originalAmount.minorUnits()) {
            throw new DomainException("reservation remaining amount exceeds original amount");
        }
        Objects.requireNonNull(expiresAt, "expiresAt is required");
        Objects.requireNonNull(useReference, "useReference is required");
        Objects.requireNonNull(businessReason, "businessReason is required");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(reservedAt, "reservedAt is required");
        Objects.requireNonNull(updatedAt, "updatedAt is required");
        if (!expiresAt.isAfter(reservedAt)) throw new DomainException("reservationExpiresAt must be after reservedAt");
        if (status == BenefitReservationStatus.ACTIVE && remainingAmount.minorUnits() == 0) {
            throw new DomainException("active reservation must retain a positive amount");
        }
        if (status != BenefitReservationStatus.ACTIVE && remainingAmount.minorUnits() != 0) {
            throw new DomainException("closed reservation must have zero remaining amount");
        }
    }

    public static BenefitReservation reserve(
        String reservationRef,
        PromotionInstrument benefit,
        Money amount,
        Instant expiresAt,
        BenefitUseReference useReference,
        BusinessReason reason,
        Instant now
    ) {
        return new BenefitReservation(
            reservationRef,
            benefit.benefitId(),
            benefit.accountId(),
            amount,
            amount,
            expiresAt,
            useReference,
            reason,
            BenefitReservationStatus.ACTIVE,
            now,
            now
        );
    }

    public boolean sameRequest(Money amount, Instant requestedExpiresAt, BenefitUseReference requestedUse, BusinessReason reason) {
        return originalAmount.equals(amount)
            && expiresAt.equals(requestedExpiresAt)
            && useReference.equals(requestedUse)
            && businessReason.equals(reason);
    }

    public BenefitReservation consume(Money amount, Instant now) {
        requireActive(now);
        originalAmount.requireSameCurrency(amount);
        amount.requirePositive("amount");
        if (remainingAmount.minorUnits() < amount.minorUnits()) {
            throw new DomainException("redemption exceeds reserved amount");
        }
        Money remaining = remainingAmount.minus(amount);
        BenefitReservationStatus next = remaining.minorUnits() == 0
            ? BenefitReservationStatus.CONSUMED
            : BenefitReservationStatus.ACTIVE;
        return transition(remaining, next, now);
    }

    public BenefitReservation release(Money amount, Instant now) {
        requireActive(now);
        originalAmount.requireSameCurrency(amount);
        amount.requirePositive("amount");
        if (remainingAmount.minorUnits() < amount.minorUnits()) {
            throw new DomainException("release exceeds reserved amount");
        }
        Money remaining = remainingAmount.minus(amount);
        BenefitReservationStatus next = remaining.minorUnits() == 0
            ? BenefitReservationStatus.RELEASED
            : BenefitReservationStatus.ACTIVE;
        return transition(remaining, next, now);
    }

    public BenefitReservation expire(Instant now) {
        if (status != BenefitReservationStatus.ACTIVE) return this;
        if (now.isBefore(expiresAt)) throw new DomainException("reservation is not expired yet");
        return transition(Money.zero(originalAmount.currency()), BenefitReservationStatus.EXPIRED, now);
    }

    public BenefitReservation expireWithBenefit(Instant now) {
        if (status != BenefitReservationStatus.ACTIVE) return this;
        return transition(Money.zero(originalAmount.currency()), BenefitReservationStatus.EXPIRED, now);
    }

    private BenefitReservation transition(Money remaining, BenefitReservationStatus next, Instant now) {
        return new BenefitReservation(
            reservationRef, benefitId, accountId, originalAmount, remaining, expiresAt, useReference,
            businessReason, next, reservedAt, now
        );
    }

    private void requireActive(Instant now) {
        if (status != BenefitReservationStatus.ACTIVE) {
            throw new DomainException("reservation is " + status);
        }
        if (!now.isBefore(expiresAt)) throw new DomainException("reservation has expired");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new DomainException(name + " is required");
    }
}
