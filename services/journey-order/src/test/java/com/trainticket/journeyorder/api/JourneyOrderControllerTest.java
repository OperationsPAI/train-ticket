package com.trainticket.journeyorder.api;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trainticket.journeyorder.RequestContextFilter;
import com.trainticket.journeyorder.RuntimeTracer;
import com.trainticket.platformkit.PlatformKitConfiguration;
import com.trainticket.platformkit.http.PlatformKitExceptionHandler;
import com.trainticket.platformkit.idempotency.UuidV7;
import com.trainticket.journeyorder.application.port.in.CancelJourneyOrderRequest;
import com.trainticket.journeyorder.application.port.in.CancelJourneyOrderResult;
import com.trainticket.journeyorder.application.port.in.JourneyOrderRequest;
import com.trainticket.journeyorder.application.port.in.JourneyOrderResult;
import com.trainticket.journeyorder.application.port.in.JourneyOrderService;
import com.trainticket.journeyorder.application.port.in.OrderListResult;
import com.trainticket.journeyorder.application.service.OrderManagementService;
import com.trainticket.journeyorder.domain.DomainRuleViolation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = JourneyOrderController.class)
@AutoConfigureMockMvc
@Import({PlatformKitConfiguration.class, PlatformKitExceptionHandler.class, RequestContextFilter.class})
class JourneyOrderControllerTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-05T10:00:00Z");
    private static final Instant CANCELLED_AT = Instant.parse("2026-07-05T11:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JourneyOrderService orderService;

    @MockitoBean
    private RuntimeTracer runtimeTracer;

    @Test
    void createOrderHappyPath() throws Exception {
        when(orderService.createOrder(
            eq(new JourneyOrderRequest("account-1", "offer-1", 1, List.of("tvl-1"), List.of("seg-1"))),
            eq("0194f2e0-7b3e-7001-8284-5c26e8b0a001"),
            eq("corr-test")
        )).thenReturn(orderResult("ord-123", "account-1", "offer-1", "CREATED"));

        mockMvc.perform(post("/api/v1/journey-orders")
                .header("Idempotency-Key", "0194f2e0-7b3e-7001-8284-5c26e8b0a001")
                .header("X-Correlation-Id", "corr-test")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "accountId": "account-1",
                      "offerId": "offer-1",
                      "offerVersion": 1,
                      "travelerRefs": ["tvl-1"],
                      "segmentRefs": ["seg-1"]
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(header().string("X-Correlation-Id", "corr-test"))
            .andExpect(jsonPath("$.orderId").value("ord-123"))
            .andExpect(jsonPath("$.accountId").value("account-1"))
            .andExpect(jsonPath("$.offerId").value("offer-1"))
            .andExpect(jsonPath("$.status").value("CREATED"))
            .andExpect(jsonPath("$.monetarySummary.subtotal.currency").value("CNY"))
            .andExpect(jsonPath("$.monetarySummary.subtotal.minorUnits").value(10000))
            .andExpect(jsonPath("$.travelerRefs[0]").value("tvl-1"))
            .andExpect(jsonPath("$.segmentRefs[0]").value("seg-1"))
            .andExpect(jsonPath("$.createdAt").value("2026-07-05T10:00:00Z"));
    }

    @Test
    void createOrderIdempotentReplayReturnsOriginal() throws Exception {
        var replayed = orderResult("ord-replay", "account-1", "offer-1", "CREATED");
        when(orderService.createOrder(any(JourneyOrderRequest.class), eq("0194f2e0-7b3e-7002-8284-5c26e8b0a002"), eq("corr-test")))
            .thenReturn(replayed)
            .thenReturn(replayed);

        String body = """
            {
              "accountId": "account-1",
              "offerId": "offer-1",
              "offerVersion": 1,
              "travelerRefs": ["tvl-1"],
              "segmentRefs": ["seg-1"]
            }
            """;

        mockMvc.perform(post("/api/v1/journey-orders")
                .header("Idempotency-Key", "0194f2e0-7b3e-7002-8284-5c26e8b0a002")
                .header("X-Correlation-Id", "corr-test")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.orderId").value("ord-replay"));

        mockMvc.perform(post("/api/v1/journey-orders")
                .header("Idempotency-Key", "0194f2e0-7b3e-7002-8284-5c26e8b0a002")
                .header("X-Correlation-Id", "corr-test")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.orderId").value("ord-replay"));
    }

    @Test
    void createOrderValidationFailureUsesCanonicalBody() throws Exception {
        mockMvc.perform(post("/api/v1/journey-orders")
                .header("Idempotency-Key", "0194f2e0-7b3e-7003-8284-5c26e8b0a003")
                .header("X-Correlation-Id", "corr-validation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "accountId": "",
                      "offerId": "offer-1",
                      "offerVersion": 0,
                      "travelerRefs": [],
                      "segmentRefs": ["seg-1"]
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.message").exists())
            .andExpect(jsonPath("$.correlationId").value("corr-validation"))
            .andExpect(jsonPath("$.details").isMap());
    }

    @Test
    void malformedBodyUsesCanonicalValidationBody() throws Exception {
        mockMvc.perform(post("/api/v1/journey-orders")
                .header("Idempotency-Key", "0194f2e0-7b3e-7004-8284-5c26e8b0a004")
                .header("X-Correlation-Id", "corr-malformed")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not-json"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void invalidParameterTypeUsesCanonicalValidationBody() throws Exception {
        mockMvc.perform(get("/api/v1/journey-orders")
                .header("X-Correlation-Id", "corr-param")
                .param("limit", "not-a-number"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.message").exists())
            .andExpect(jsonPath("$.correlationId").value("corr-param"));
    }

    @Test
    void missingIdempotencyKeyUsesCanonicalValidationBody() throws Exception {
        mockMvc.perform(post("/api/v1/journey-orders")
                .header("X-Correlation-Id", "corr-missing-idem")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "accountId": "account-1",
                      "offerId": "offer-1",
                      "offerVersion": 1,
                      "travelerRefs": ["tvl-1"],
                      "segmentRefs": ["seg-1"]
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.message").value("Idempotency-Key header must be a UUID v7"))
            .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void reusedIdempotencyKeyUsesCanonical422Body() throws Exception {
        when(orderService.createOrder(any(JourneyOrderRequest.class), eq("0194f2e0-7b3e-7005-8284-5c26e8b0a005"), eq("corr-reused")))
            .thenThrow(new OrderManagementService.IdempotencyKeyReused("Idempotency-Key was reused with a different create order request"));

        mockMvc.perform(post("/api/v1/journey-orders")
                .header("Idempotency-Key", "0194f2e0-7b3e-7005-8284-5c26e8b0a005")
                .header("X-Correlation-Id", "corr-reused")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "accountId": "account-1",
                      "offerId": "offer-2",
                      "offerVersion": 1,
                      "travelerRefs": ["tvl-1"],
                      "segmentRefs": ["seg-1"]
                    }
                    """))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
            .andExpect(jsonPath("$.correlationId").value("corr-reused"));
    }

    @Test
    void getOrderReturnsNotFoundThroughCanonicalHandler() throws Exception {
        when(orderService.getOrder("ord-missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/journey-orders/{orderId}", "ord-missing")
                .header("X-Correlation-Id", "corr-not-found"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Order not found: ord-missing"))
            .andExpect(jsonPath("$.correlationId").value("corr-not-found"));
    }

    @Test
    void getOrderReturnsCreatedOrder() throws Exception {
        when(orderService.getOrder("ord-123")).thenReturn(Optional.of(orderResult("ord-123", "account-1", "offer-1", "CREATED")));

        mockMvc.perform(get("/api/v1/journey-orders/{orderId}", "ord-123")
                .header("X-Correlation-Id", "corr-get"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.orderId").value("ord-123"))
            .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void listOrdersSupportsPagination() throws Exception {
        when(orderService.listOrders("account-1", null, 1, 0))
            .thenReturn(new OrderListResult(
                List.of(orderResult("ord-1", "account-1", "offer-1", "CREATED")),
                2,
                1,
                0
            ));

        mockMvc.perform(get("/api/v1/journey-orders")
                .header("X-Correlation-Id", "corr-list")
                .param("accountId", "account-1")
                .param("limit", "1")
                .param("offset", "0"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.limit").value(1))
            .andExpect(jsonPath("$.offset").value(0))
            .andExpect(jsonPath("$.items[0].orderId").value("ord-1"));
    }

    @Test
    void cancelOrderReturnsCancelledStatus() throws Exception {
        when(orderService.cancelOrder(
            eq(new CancelJourneyOrderRequest("ord-123", "change of plans")),
            eq("0194f2e0-7b3e-7006-8284-5c26e8b0a006"),
            eq("corr-cancel")
        )).thenReturn(new CancelJourneyOrderResult("ord-123", "CANCELLED", CANCELLED_AT));

        mockMvc.perform(post("/api/v1/journey-orders/{orderId}/cancel", "ord-123")
                .header("Idempotency-Key", "0194f2e0-7b3e-7006-8284-5c26e8b0a006")
                .header("X-Correlation-Id", "corr-cancel")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"change of plans\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.orderId").value("ord-123"))
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.cancelledAt").value("2026-07-05T11:00:00Z"));
    }

    @Test
    void domainInvariantViolationSurfacesAsDomainRuleViolation() throws Exception {
        when(orderService.cancelOrder(any(CancelJourneyOrderRequest.class), eq("0194f2e0-7b3e-7007-8284-5c26e8b0a007"), eq("corr-domain")))
            .thenThrow(new DomainRuleViolation("completed JourneyOrder cannot be cancelled"));

        mockMvc.perform(post("/api/v1/journey-orders/{orderId}/cancel", "ord-123")
                .header("Idempotency-Key", "0194f2e0-7b3e-7007-8284-5c26e8b0a007")
                .header("X-Correlation-Id", "corr-domain")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"change of plans\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("DOMAIN_RULE_VIOLATION"))
            .andExpect(jsonPath("$.message").value("completed JourneyOrder cannot be cancelled"))
            .andExpect(jsonPath("$.correlationId").value("corr-domain"));
    }

    @Test
    void generatedCorrelationIdIsCanonicalWhenHeaderAbsent() throws Exception {
        when(orderService.getOrder("ord-123")).thenReturn(Optional.of(orderResult("ord-123", "account-1", "offer-1", "CREATED")));

        mockMvc.perform(get("/api/v1/journey-orders/{orderId}", "ord-123"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", startsWith("corr-")));

        String generated = mockMvc.perform(get("/api/v1/journey-orders/{orderId}", "ord-123"))
            .andReturn().getResponse().getHeader("X-Correlation-Id");
        org.junit.jupiter.api.Assertions.assertTrue(UuidV7.isValid(generated.substring("corr-".length())));
    }

    private static JourneyOrderResult orderResult(String orderId, String accountId, String offerId, String status) {
        return new JourneyOrderResult(
            orderId,
            accountId,
            offerId,
            new JourneyOrderResult.MonetarySummaryDto("CNY", 10000L, 0L, 0L, 0L, 0L, 10000L),
            status,
            List.of("tvl-1"),
            List.of("seg-1"),
            CREATED_AT
        );
    }
}
