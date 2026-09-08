package com.trainticket.journeyorder.application.port.out;

import com.trainticket.journeyorder.domain.Money;
import java.util.Optional;

/**
 * Reads the priced total of the offer an order is being created from.
 *
 * WHY THIS EXISTS
 * ---------------
 * `OrderManagementService.createOrder` used to build its order items with a
 * literal `Money.of("CNY", "100.00")`, so an order's monetary summary bore no
 * relation to what the customer was quoted. On the live cluster every
 * `post_sales_policy_contexts` row held 100.00 or 200.00 and none held any other
 * value, whatever the published fare rules said.
 *
 * That is not only a display problem: post-sales derives the refund penalty base
 * from the order's fare, so every refund was priced against 100.00 with the
 * correct tier percentage applied to the wrong number.
 *
 * The port is deliberately narrow -- one lookup, returning Optional rather than
 * throwing -- because order creation must not become unavailable when
 * offer-management is. See the adapter for what happens on a miss.
 */
public interface OfferPricePort {
    /**
     * The offer's total, or empty when it cannot be read.
     *
     * Empty covers both "no such offer" and "offer-management is unreachable".
     * The caller cannot distinguish them and should not try: in both cases it has
     * no priced total, and the decision about what to do with that is the
     * caller's.
     */
    Optional<Money> totalFor(String offerId, int offerVersion);
}
