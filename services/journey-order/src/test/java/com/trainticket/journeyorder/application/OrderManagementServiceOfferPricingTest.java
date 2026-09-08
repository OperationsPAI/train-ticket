package com.trainticket.journeyorder.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.trainticket.journeyorder.application.port.in.JourneyOrderRequest;
import com.trainticket.journeyorder.application.port.out.IdentityVerificationPort;
import com.trainticket.journeyorder.application.port.out.OfferPricePort;
import com.trainticket.journeyorder.application.service.InMemoryJourneyOrderStateRepository;
import com.trainticket.journeyorder.application.service.OrderManagementService;
import com.trainticket.journeyorder.domain.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * An order's fare must come from the offer it was created against.
 *
 * It used to be a literal `Money.of("CNY", "100.00")`, which is worse than a
 * display bug: post-sales derives the refund penalty base from the order's fare,
 * so every refund was priced against 100.00 with the correct tier percentage
 * applied to the wrong number. Every post_sales_policy_contexts row on the live
 * cluster held 100.00 or 200.00 and none held anything else, whatever fare rules
 * were published.
 */
class OrderManagementServiceOfferPricingTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC);
    private static final IdentityVerificationPort PASS_IDENTITY =
        (request, orderIntentId, idempotencyKey, correlationId) ->
            new IdentityVerificationPort.PreOrderCheckResult("PASS", "poc-test");

    private static OrderManagementService serviceWith(OfferPricePort offerPrice) {
        return new OrderManagementService(
            envelope -> { },
            CLOCK,
            new InMemoryJourneyOrderStateRepository(),
            PASS_IDENTITY,
            offerPrice);
    }

    @Test
    void singleTravelerOrderCarriesTheOfferTotal() {
        OrderManagementService service = serviceWith(
            (offerId, offerVersion) -> Optional.of(Money.fromMinorUnits(12_000, "CNY")));

        var result = service.createOrder(
            new JourneyOrderRequest("acc-1", "off-1", 1, List.of("tvl-1"), List.of("seg-1")),
            "idem-offer-single", "corr-1");

        assertThat(result.monetarySummary().payableTotal())
            .as("the order must be priced at the 120.00 the offer quoted, not a 100.00 placeholder")
            .isEqualTo(12_000);
    }

    @Test
    void multiTravelerOrderSplitsTheTotalWithoutLosingMinorUnits() {
        // 10001 across 3 travelers does not divide evenly. Rounding each share
        // independently would sum to 10_002 or 9_999; the remainder must land on
        // one item so the total is preserved exactly, because the payable total is
        // derived from the sum of the items.
        OrderManagementService service = serviceWith(
            (offerId, offerVersion) -> Optional.of(Money.fromMinorUnits(10_001, "CNY")));

        var result = service.createOrder(
            new JourneyOrderRequest("acc-1", "off-2", 1,
                List.of("tvl-1", "tvl-2", "tvl-3"), List.of("seg-1")),
            "idem-offer-split", "corr-1");

        assertThat(result.monetarySummary().payableTotal())
            .as("the split items must sum to exactly the offer total")
            .isEqualTo(10_001);
    }

    @Test
    void fallsBackToAPlaceholderWhenTheOfferCannotBePriced() {
        // Order creation must not fail because offer-management is unavailable.
        // The adapter logs a warning in this case; the order is still created.
        OrderManagementService service = serviceWith((offerId, offerVersion) -> Optional.empty());

        var result = service.createOrder(
            new JourneyOrderRequest("acc-1", "off-3", 1, List.of("tvl-1"), List.of("seg-1")),
            "idem-offer-missing", "corr-1");

        assertThat(result.monetarySummary().payableTotal())
            .as("an unreadable offer must still yield an order rather than an error")
            .isPositive();
    }
}
