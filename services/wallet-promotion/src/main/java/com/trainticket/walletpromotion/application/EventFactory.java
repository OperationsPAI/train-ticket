package com.trainticket.walletpromotion.application;

import com.trainticket.walletpromotion.domain.BenefitRedemption;
import com.trainticket.walletpromotion.domain.Money;
import com.trainticket.walletpromotion.domain.PromotionInstrument;
import com.trainticket.walletpromotion.domain.WalletBalanceDelta;
import com.trainticket.walletpromotion.domain.WalletPromotionEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class EventFactory {
    private EventFactory() {
    }

    /** Wire payload timestamps are RFC3339 UTC strings per repo invariants;
     * raw Instants would serialize as epoch decimals. */
    private static String iso(Instant instant) {
        return java.time.format.DateTimeFormatter.ISO_INSTANT.format(instant);
    }

    static WalletPromotionEvent issued(PromotionInstrument benefit, WalletBalanceDelta delta, Instant occurredAt) {
        Map<String, Object> payload = basePayload(benefit, delta);
        payload.put("issuedAmount", benefit.issuedAmount());
        payload.put("availableAmount", benefit.availableAmount());
        payload.put("reservedAmount", benefit.reservedAmount());
        payload.put("issuanceSource", benefit.issuanceSource());
        payload.put("caseId", benefit.caseId());
        payload.put("applicableScope", benefit.applicableScope());
        payload.put("redemptionRule", benefit.redemptionRule());
        payload.put("revocationRule", benefit.revocationRule());
        payload.put("validFrom", iso(benefit.validFrom()));
        payload.put("validUntil", iso(benefit.validUntil()));
        payload.put("issuedAt", iso(occurredAt));
        return event("BenefitIssued", benefit, payload, occurredAt);
    }

    static WalletPromotionEvent reserved(
        PromotionInstrument benefit,
        ReserveBenefitCommand command,
        WalletBalanceDelta delta,
        Instant occurredAt
    ) {
        Map<String, Object> payload = basePayload(benefit, delta);
        payload.put("reservedAmount", command.amount());
        payload.put("reservationRef", command.reservationRef());
        payload.put("reservationExpiresAt", iso(command.reservationExpiresAt()));
        payload.put("availableAmount", benefit.availableAmount());
        payload.put("totalReservedAmount", benefit.reservedAmount());
        payload.put("reservedAt", iso(occurredAt));
        return event("BenefitReserved", benefit, payload, occurredAt);
    }

    static WalletPromotionEvent redeemed(
        PromotionInstrument benefit,
        BenefitRedemption redemption,
        String reservationRef,
        WalletBalanceDelta delta,
        Instant occurredAt
    ) {
        Map<String, Object> payload = basePayload(benefit, delta);
        payload.put("redemptionId", redemption.redemptionId());
        payload.put("redeemedAmount", redemption.amount());
        payload.put("redemptionRef", redemption.redemptionRef());
        payload.put("reservationRef", reservationRef);
        payload.put("availableAmount", benefit.availableAmount());
        payload.put("reservedAmount", benefit.reservedAmount());
        payload.put("totalRedeemedAmount", benefit.redeemedAmount());
        payload.put("redeemedAt", iso(occurredAt));
        return event("BenefitRedeemed", benefit, payload, occurredAt);
    }

    static WalletPromotionEvent released(
        PromotionInstrument benefit,
        ReleaseBenefitCommand command,
        WalletBalanceDelta delta,
        Instant occurredAt
    ) {
        Map<String, Object> payload = basePayload(benefit, delta);
        payload.put("releasedAmount", command.amount());
        payload.put("reservationRef", command.reservationRef());
        payload.put("availableAmount", benefit.availableAmount());
        payload.put("reservedAmount", benefit.reservedAmount());
        payload.put("releasedAt", iso(occurredAt));
        return event("BenefitReservationReleased", benefit, payload, occurredAt);
    }

    static WalletPromotionEvent expired(
        PromotionInstrument benefit,
        Money expiredAmount,
        WalletBalanceDelta delta,
        Instant occurredAt
    ) {
        Map<String, Object> payload = basePayload(benefit, delta);
        payload.put("expiredAmount", expiredAmount);
        payload.put("validUntil", iso(benefit.validUntil()));
        payload.put("availableAmount", benefit.availableAmount());
        payload.put("reservedAmount", benefit.reservedAmount());
        payload.put("expiredAt", iso(occurredAt));
        return event("BenefitExpired", benefit, payload, occurredAt);
    }

    static WalletPromotionEvent revoked(
        PromotionInstrument benefit,
        Money revokedAmount,
        WalletBalanceDelta delta,
        Instant occurredAt
    ) {
        Map<String, Object> payload = basePayload(benefit, delta);
        payload.put("revokedAmount", revokedAmount);
        payload.put("availableAmount", benefit.availableAmount());
        payload.put("reservedAmount", benefit.reservedAmount());
        payload.put("revokedAt", iso(occurredAt));
        return event("BenefitRevoked", benefit, payload, occurredAt);
    }

    static WalletPromotionEvent reversed(
        PromotionInstrument benefit,
        String redemptionId,
        String reversalId,
        Money reversedAmount,
        WalletBalanceDelta delta,
        Instant occurredAt
    ) {
        Map<String, Object> payload = basePayload(benefit, delta);
        payload.put("redemptionId", redemptionId);
        payload.put("reversalId", reversalId);
        payload.put("reversedAmount", reversedAmount);
        payload.put("availableAmount", benefit.availableAmount());
        payload.put("reservedAmount", benefit.reservedAmount());
        payload.put("totalRedeemedAmount", benefit.redeemedAmount());
        payload.put("reversedAt", iso(occurredAt));
        return event("BenefitRedemptionReversed", benefit, payload, occurredAt);
    }

    private static Map<String, Object> basePayload(PromotionInstrument benefit, WalletBalanceDelta delta) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("benefitId", benefit.benefitId());
        payload.put("accountId", benefit.accountId());
        payload.put("walletAccountId", benefit.walletAccountId());
        payload.put("benefitType", benefit.benefitType());
        payload.put("balanceType", benefit.balanceType());
        payload.put("businessReason", benefit.businessReason());
        payload.put("walletBalanceDelta", delta);
        payload.put("status", benefit.status());
        payload.put("aggregateVersion", benefit.version());
        return payload;
    }

    private static WalletPromotionEvent event(
        String eventType,
        PromotionInstrument benefit,
        Map<String, Object> payload,
        Instant occurredAt
    ) {
        payload.values().removeIf(Objects::isNull);
        return new WalletPromotionEvent(eventType, payload, occurredAt, benefit.version());
    }
}
