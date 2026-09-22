package com.trainticket.postsales.adapters.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trainticket.postsales.application.AdjustmentQuotePort.AdjustmentQuoteRequest;
import com.trainticket.platformkit.idempotency.UuidV7;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

class PurchasedOfferAdjustmentTest {
    @Test
    @EnabledIfEnvironmentVariable(named="TEST_LIVE_BASE_URL_TEMPLATE", matches=".+")
    void adjustmentUsesThePurchasedOfferPrice() {
        String template = System.getenv("TEST_LIVE_BASE_URL_TEMPLATE");
        var client = new FarePricingAdjustmentQuoteClient(template.formatted("fare-pricing"),
            template.formatted("journey-order"), template.formatted("offer-management"), RestClient.builder());
        var result = client.compute(new AdjustmentQuoteRequest("REFUND",
            List.of(System.getenv("TEST_ENTITLEMENT_ID")), System.getenv("TEST_ORDER_ID"),
            List.of(System.getenv("TEST_SEGMENT_ID")), UuidV7.generate())).orElseThrow();
        assertEquals("CNY", result.refundableCurrency());
        assertEquals("QUOTED", result.status());
        assertTrue(result.refundableMinorUnits() <= Long.parseLong(System.getenv("TEST_PAID_MINOR_UNITS")));
        assertTrue(result.refundableMinorUnits() > 0);
    }
}
