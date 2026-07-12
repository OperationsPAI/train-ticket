package com.trainticket.walletpromotion.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.trainticket.walletpromotion.domain.BenefitRedemption;
import com.trainticket.walletpromotion.domain.Money;
import com.trainticket.walletpromotion.domain.PromotionInstrument;
import com.trainticket.walletpromotion.domain.PromotionStatus;
import com.trainticket.walletpromotion.domain.ReasonType;
import com.trainticket.walletpromotion.domain.WalletBalanceDelta;
import com.trainticket.walletpromotion.domain.WalletPromotionEvent;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventFactoryTest {
    private static final Instant AT = Instant.parse("2026-07-09T10:15:30Z");

    @Test
    void benefitIssuedPayloadMatchesContractFields() {
        PromotionInstrument benefit = PromotionInstrument.issue(
            "ben-1",
            "acc-1",
            "wac-1",
            com.trainticket.walletpromotion.domain.BenefitType.BALANCE,
            com.trainticket.walletpromotion.domain.BalanceType.PROMOTION_CREDIT,
            new Money("USD", 100),
            com.trainticket.walletpromotion.domain.IssuanceSource.MANUAL_OPS,
            null,
            new com.trainticket.walletpromotion.domain.ApplicableScope("ANY_TRIP", null, "USD"),
            new com.trainticket.walletpromotion.domain.RedemptionRule(false, null, false),
            new com.trainticket.walletpromotion.domain.RevocationRule(null),
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-08-01T00:00:00Z"),
            WalletPromotionServiceTest.reason(ReasonType.MANUAL_OPS, "TEST", "MANUAL_ACTION", "act-1"),
            AT
        );
        WalletBalanceDelta delta = delta(benefit, 100, 0, 0, 100, 0);

        WalletPromotionEvent event = EventFactory.issued(benefit, delta, AT);

        assertCommon(event, "BenefitIssued", benefit, PromotionStatus.ISSUED);
        assertPayloadContainsExactly(event.payload(),
            "benefitId", "accountId", "walletAccountId", "benefitType", "balanceType", "issuedAmount",
            "availableAmount", "reservedAmount", "issuanceSource", "applicableScope", "redemptionRule",
            "revocationRule", "validFrom", "validUntil", "businessReason", "walletBalanceDelta", "issuedAt",
            "status", "aggregateVersion"
        );
        assertThat(event.payload()).containsEntry("issuedAmount", benefit.issuedAmount());
        assertThat(event.payload()).containsEntry("issuedAt", java.time.format.DateTimeFormatter.ISO_INSTANT.format(AT));
    }

    @Test
    void benefitReservedPayloadMatchesContractFields() {
        PromotionInstrument benefit = baseBenefit().reserve(
            new Money("USD", 25),
            WalletPromotionServiceTest.reason(ReasonType.ORDER_PURCHASE, "RESERVE", "ORDER", "ord-1"),
            AT
        );
        ReserveBenefitCommand command = new ReserveBenefitCommand(
            new Money("USD", 25),
            "ord-1",
            AT.plusSeconds(600),
            benefit.businessReason()
        );
        WalletPromotionEvent event = EventFactory.reserved(benefit, command, delta(benefit, -25, 25, 0, 75, 25), AT);

        assertCommon(event, "BenefitReserved", benefit, PromotionStatus.RESERVED);
        assertPayloadContainsExactly(event.payload(),
            "benefitId", "accountId", "walletAccountId", "benefitType", "balanceType", "reservedAmount",
            "reservationRef", "reservationExpiresAt", "availableAmount", "totalReservedAmount", "businessReason",
            "walletBalanceDelta", "reservedAt", "status", "aggregateVersion"
        );
        assertThat(event.payload()).containsEntry("reservationRef", "ord-1");
    }

    @Test
    void benefitRedeemedPayloadMatchesContractFields() {
        PromotionInstrument benefit = baseBenefit().redeem(
            new Money("USD", 100),
            WalletPromotionServiceTest.reason(ReasonType.ORDER_PURCHASE, "USE", "ORDER", "ord-2"),
            AT,
            false
        );
        BenefitRedemption redemption = new BenefitRedemption(
            "brd-1",
            benefit.benefitId(),
            benefit.accountId(),
            new Money("USD", 100),
            "ord-2",
            benefit.businessReason(),
            AT,
            0
        );
        WalletPromotionEvent event = EventFactory.redeemed(
            benefit,
            redemption,
            null,
            delta(benefit, -100, 0, 100, 0, 0),
            AT
        );

        assertCommon(event, "BenefitRedeemed", benefit, PromotionStatus.REDEEMED);
        assertPayloadContainsExactly(event.payload(),
            "benefitId", "accountId", "walletAccountId", "benefitType", "balanceType", "redemptionId",
            "redeemedAmount", "redemptionRef", "availableAmount", "reservedAmount", "totalRedeemedAmount",
            "businessReason", "walletBalanceDelta", "redeemedAt", "status", "aggregateVersion"
        );
        assertThat(event.payload()).containsEntry("redemptionId", "brd-1");
    }

    @Test
    void benefitReservationReleasedPayloadMatchesContractFields() {
        PromotionInstrument benefit = baseBenefit()
            .reserve(new Money("USD", 30), WalletPromotionServiceTest.reason(ReasonType.ORDER_PURCHASE, "RESERVE", "ORDER", "ord-3"), AT)
            .release(new Money("USD", 30), WalletPromotionServiceTest.reason(ReasonType.RESERVATION_TIMEOUT, "TIMEOUT", "ORDER", "ord-3"), AT.plusSeconds(1));
        ReleaseBenefitCommand command = new ReleaseBenefitCommand(new Money("USD", 30), "ord-3", benefit.businessReason());
        WalletPromotionEvent event = EventFactory.released(benefit, command, delta(benefit, 30, -30, 0, 100, 0), AT);

        assertCommon(event, "BenefitReservationReleased", benefit, PromotionStatus.RELEASED);
        assertPayloadContainsExactly(event.payload(),
            "benefitId", "accountId", "walletAccountId", "benefitType", "balanceType", "releasedAmount",
            "reservationRef", "availableAmount", "reservedAmount", "businessReason", "walletBalanceDelta",
            "releasedAt", "status", "aggregateVersion"
        );
        assertThat(event.payload()).containsEntry("reservationRef", "ord-3");
    }

    @Test
    void benefitExpiredPayloadMatchesContractFields() {
        PromotionInstrument benefit = baseBenefit().expire(
            WalletPromotionServiceTest.reason(ReasonType.SYSTEM_EXPIRY, "VALIDITY_ELAPSED", "SCHEDULER_JOB", "expiry"),
            Instant.parse("2026-08-01T00:00:00Z")
        );
        WalletPromotionEvent event = EventFactory.expired(benefit, new Money("USD", 100), delta(benefit, -100, 0, 0, 0, 0), AT);

        assertCommon(event, "BenefitExpired", benefit, PromotionStatus.EXPIRED);
        assertPayloadContainsExactly(event.payload(),
            "benefitId", "accountId", "walletAccountId", "benefitType", "balanceType", "expiredAmount",
            "validUntil", "availableAmount", "reservedAmount", "businessReason", "walletBalanceDelta",
            "expiredAt", "status", "aggregateVersion"
        );
        assertThat(event.payload()).containsEntry("expiredAmount", new Money("USD", 100));
    }

    @Test
    void benefitRevokedPayloadMatchesContractFields() {
        PromotionInstrument benefit = baseBenefit().revoke(
            WalletPromotionServiceTest.reason(ReasonType.CUSTOMER_SERVICE_ADJUSTMENT, "REVOKE", "MANUAL_ACTION", "act-2"),
            AT
        );
        WalletPromotionEvent event = EventFactory.revoked(benefit, new Money("USD", 100), delta(benefit, -100, 0, 0, 0, 0), AT);

        assertCommon(event, "BenefitRevoked", benefit, PromotionStatus.REVOKED);
        assertPayloadContainsExactly(event.payload(),
            "benefitId", "accountId", "walletAccountId", "benefitType", "balanceType", "revokedAmount",
            "availableAmount", "reservedAmount", "businessReason", "walletBalanceDelta", "revokedAt",
            "status", "aggregateVersion"
        );
        assertThat(event.payload()).containsEntry("revokedAmount", new Money("USD", 100));
    }

    @Test
    void benefitRedemptionReversedPayloadMatchesContractFields() {
        PromotionInstrument benefit = baseBenefit()
            .redeem(new Money("USD", 100), WalletPromotionServiceTest.reason(ReasonType.ORDER_PURCHASE, "USE", "ORDER", "ord-4"), AT, false)
            .reverse(new Money("USD", 100), WalletPromotionServiceTest.reason(ReasonType.REVERSAL, "REFUND", "ORDER", "ord-4"), AT.plusSeconds(1));
        WalletPromotionEvent event = EventFactory.reversed(
            benefit,
            "brd-4",
            "brr-4",
            new Money("USD", 100),
            delta(benefit, 100, 0, -100, 100, 0),
            AT
        );

        assertCommon(event, "BenefitRedemptionReversed", benefit, PromotionStatus.REVERSED);
        assertPayloadContainsExactly(event.payload(),
            "benefitId", "accountId", "walletAccountId", "benefitType", "balanceType", "redemptionId",
            "reversalId", "reversedAmount", "availableAmount", "reservedAmount", "totalRedeemedAmount",
            "businessReason", "walletBalanceDelta", "reversedAt", "status", "aggregateVersion"
        );
        assertThat(event.payload()).containsEntry("reversalId", "brr-4");
    }

    private static PromotionInstrument baseBenefit() {
        return PromotionInstrument.issue(
            "ben-1",
            "acc-1",
            "wac-1",
            com.trainticket.walletpromotion.domain.BenefitType.BALANCE,
            com.trainticket.walletpromotion.domain.BalanceType.PROMOTION_CREDIT,
            new Money("USD", 100),
            com.trainticket.walletpromotion.domain.IssuanceSource.MANUAL_OPS,
            null,
            new com.trainticket.walletpromotion.domain.ApplicableScope("ANY_TRIP", null, "USD"),
            new com.trainticket.walletpromotion.domain.RedemptionRule(false, null, false),
            new com.trainticket.walletpromotion.domain.RevocationRule(null),
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-08-01T00:00:00Z"),
            WalletPromotionServiceTest.reason(ReasonType.MANUAL_OPS, "TEST", "MANUAL_ACTION", "act-1"),
            AT
        );
    }

    private static WalletBalanceDelta delta(
        PromotionInstrument benefit,
        long availableDelta,
        long reservedDelta,
        long redeemedDelta,
        long availableAfter,
        long reservedAfter
    ) {
        return new WalletBalanceDelta(
            benefit.walletAccountId(),
            benefit.balanceType(),
            new Money("USD", availableDelta),
            new Money("USD", reservedDelta),
            new Money("USD", redeemedDelta),
            new Money("USD", availableAfter),
            new Money("USD", reservedAfter),
            "wle-1"
        );
    }

    private static void assertCommon(
        WalletPromotionEvent event,
        String eventType,
        PromotionInstrument benefit,
        PromotionStatus status
    ) {
        assertThat(event.eventType()).isEqualTo(eventType);
        assertThat(event.occurredAt()).isEqualTo(AT);
        assertThat(event.aggregateVersion()).isEqualTo(benefit.version());
        assertThat(event.payload()).containsEntry("benefitId", benefit.benefitId());
        assertThat(event.payload()).containsEntry("businessReason", benefit.businessReason());
        assertThat(event.payload()).containsEntry("status", status);
        assertThat(event.payload()).containsEntry("aggregateVersion", benefit.version());
    }

    private static void assertPayloadContainsExactly(Map<String, Object> payload, String... fields) {
        assertThat(payload.keySet()).containsExactlyInAnyOrder(fields);
    }
}
