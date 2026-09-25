package com.trainticket.walletpromotion.adapters.http;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.trainticket.platformkit.http.ApiException;
import com.trainticket.walletpromotion.domain.ApplicableScope;
import com.trainticket.walletpromotion.domain.BalanceType;
import com.trainticket.walletpromotion.domain.BenefitScopeType;
import com.trainticket.walletpromotion.domain.BenefitType;
import com.trainticket.walletpromotion.domain.BenefitUseReference;
import com.trainticket.walletpromotion.domain.BusinessReason;
import com.trainticket.walletpromotion.domain.IssuanceSource;
import com.trainticket.walletpromotion.domain.Money;
import com.trainticket.walletpromotion.domain.PromotionInstrument;
import com.trainticket.walletpromotion.domain.ReasonType;
import com.trainticket.walletpromotion.domain.RedemptionRule;
import com.trainticket.walletpromotion.domain.RevocationRule;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AuthoritativeBenefitScopeVerifierTest {
    @Test
    void anyTripRequiresARealOrderOwnedByTheBenefitAccountAndCurrency() {
        Fixture fixture = fixture();
        fixture.order("ord-1", "acc-1", "USD", "off-1", List.of("seg-1"));

        fixture.verifier.verify(
            benefit(BenefitScopeType.ANY_TRIP, List.of()),
            new BenefitUseReference("ord-1", null, List.of()),
            reason("ORDER", "ord-1")
        );

        fixture.verify();
    }

    @Test
    void rejectsAnOrderThatDoesNotBelongToTheBenefitAccount() {
        Fixture fixture = fixture();
        fixture.order("ord-1", "acc-other", "USD", "off-1", List.of("seg-1"));

        assertThatThrownBy(() -> fixture.verifier.verify(
            benefit(BenefitScopeType.ANY_TRIP, List.of()),
            new BenefitUseReference("ord-1", null, List.of()),
            reason("ORDER", "ord-1")
        )).isInstanceOf(ApiException.class).hasMessageContaining("does not own");

        fixture.verify();
    }

    @Test
    void serviceSegmentMustBeInBothTheOrderAndTheIssuedScope() {
        Fixture fixture = fixture();
        fixture.order("ord-1", "acc-1", "USD", "off-1", List.of("seg-1", "seg-2"));

        fixture.verifier.verify(
            benefit(BenefitScopeType.SERVICE_SEGMENT, List.of("seg-2")),
            new BenefitUseReference("ord-1", null, List.of("seg-2")),
            reason("SERVICE_SEGMENT", "seg-2")
        );

        fixture.verify();
    }

    @Test
    void postSalesScopeResolvesTheCaseBackToTheSameOrderAndAccount() {
        Fixture fixture = fixture();
        fixture.order("ord-1", "acc-1", "USD", "off-1", List.of("seg-1"));
        fixture.postSales("psc-1", "ord-1", "acc-1", List.of("seg-1"));

        fixture.verifier.verify(
            benefit(BenefitScopeType.POST_SALES_CASE, List.of("psc-1")),
            new BenefitUseReference("ord-1", "psc-1", List.of("seg-1")),
            reason("POST_SALES_CASE", "psc-1")
        );

        fixture.verify();
    }

    @Test
    void productCategoryComesFromTheOrdersOfferAndFareQuote() {
        Fixture fixture = fixture();
        fixture.order("ord-1", "acc-1", "USD", "off-1", List.of("seg-1"));
        fixture.offer("off-1", "acc-1", "fq-1");
        fixture.fare("fq-1", "RAIL_STANDARD");

        fixture.verifier.verify(
            benefit(BenefitScopeType.PRODUCT_CATEGORY, List.of("RAIL_STANDARD")),
            new BenefitUseReference("ord-1", null, List.of()),
            reason("ORDER", "ord-1")
        );

        fixture.verify();
    }

    @Test
    void businessReasonCannotNameAReferenceDifferentFromTheVerifiedUse() {
        Fixture fixture = fixture();
        fixture.order("ord-1", "acc-1", "USD", "off-1", List.of("seg-1"));

        assertThatThrownBy(() -> fixture.verifier.verify(
            benefit(BenefitScopeType.ANY_TRIP, List.of()),
            new BenefitUseReference("ord-1", null, List.of()),
            reason("ORDER", "ord-other")
        )).isInstanceOf(ApiException.class).hasMessageContaining("businessReason");

        fixture.verify();
    }

    private static PromotionInstrument benefit(BenefitScopeType scopeType, List<String> references) {
        Instant issuedAt = Instant.parse("2026-09-01T00:00:00Z");
        return PromotionInstrument.issue(
            "ben-1", "acc-1", "wac-1", BenefitType.BALANCE, BalanceType.PROMOTION_CREDIT,
            new Money("USD", 100), IssuanceSource.MANUAL_OPS, null,
            new ApplicableScope(scopeType, references, "USD"), new RedemptionRule(false, null, false),
            new RevocationRule(null), issuedAt, issuedAt.plusSeconds(86400),
            new BusinessReason(ReasonType.MANUAL_OPS, "ISSUE", "MANUAL_ACTION", "act-1", null), issuedAt
        );
    }

    private static BusinessReason reason(String type, String id) {
        return new BusinessReason(ReasonType.ORDER_PURCHASE, "USE", type, id, null);
    }

    private static Fixture fixture() {
        RestClient.Builder orderBuilder = RestClient.builder();
        RestClient.Builder postSalesBuilder = RestClient.builder();
        RestClient.Builder offerBuilder = RestClient.builder();
        RestClient.Builder fareBuilder = RestClient.builder();
        MockRestServiceServer orders = MockRestServiceServer.bindTo(orderBuilder).build();
        MockRestServiceServer postSales = MockRestServiceServer.bindTo(postSalesBuilder).build();
        MockRestServiceServer offers = MockRestServiceServer.bindTo(offerBuilder).build();
        MockRestServiceServer fares = MockRestServiceServer.bindTo(fareBuilder).build();
        return new Fixture(
            new AuthoritativeBenefitScopeVerifier(
                orderBuilder.baseUrl("http://orders").build(),
                postSalesBuilder.baseUrl("http://post-sales").build(),
                offerBuilder.baseUrl("http://offers").build(),
                fareBuilder.baseUrl("http://fares").build()
            ),
            orders,
            postSales,
            offers,
            fares
        );
    }

    private record Fixture(
        AuthoritativeBenefitScopeVerifier verifier,
        MockRestServiceServer orders,
        MockRestServiceServer postSales,
        MockRestServiceServer offers,
        MockRestServiceServer fares
    ) {
        void order(String orderId, String accountId, String currency, String offerId, List<String> segments) {
            orders.expect(requestTo("http://orders/api/v1/journey-orders/" + orderId)).andExpect(method(GET))
                .andRespond(withSuccess("""
                    {"orderId":"%s","accountId":"%s","offerId":"%s","status":"CONFIRMED",
                     "segmentRefs":%s,"monetarySummary":{"currency":"%s"}}
                    """.formatted(orderId, accountId, offerId, jsonArray(segments), currency), MediaType.APPLICATION_JSON));
        }

        void postSales(String caseId, String orderId, String actorRef, List<String> segments) {
            postSales.expect(requestTo("http://post-sales/api/v1/post-sales-cases/" + caseId)).andExpect(method(GET))
                .andRespond(withSuccess("""
                    {"caseId":"%s","journeyOrderId":"%s","actorRef":"%s","scope":{"segmentRefs":%s}}
                    """.formatted(caseId, orderId, actorRef, jsonArray(segments)), MediaType.APPLICATION_JSON));
        }

        void offer(String offerId, String accountId, String quoteId) {
            offers.expect(requestTo("http://offers/api/v1/offers/" + offerId)).andExpect(method(GET))
                .andRespond(withSuccess("""
                    {"offerId":"%s","accountId":"%s","fareQuoteRef":"%s"}
                    """.formatted(offerId, accountId, quoteId), MediaType.APPLICATION_JSON));
        }

        void fare(String quoteId, String productCode) {
            fares.expect(requestTo("http://fares/api/v1/fare-quotes/" + quoteId)).andExpect(method(GET))
                .andRespond(withSuccess("""
                    {"quoteId":"%s","productCode":"%s"}
                    """.formatted(quoteId, productCode), MediaType.APPLICATION_JSON));
        }

        void verify() {
            orders.verify();
            postSales.verify();
            offers.verify();
            fares.verify();
        }

        private static String jsonArray(List<String> values) {
            return values.stream().map(value -> "\"" + value + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        }
    }
}
