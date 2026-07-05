package com.trainticket.payment.adapters.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.trainticket.payment.RequestContextFilter;
import com.trainticket.payment.application.PaymentCommandService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class PaymentControllerTest {
    private PaymentController controller;
    private PaymentExceptionHandler exceptionHandler;

    @BeforeEach
    void setUp() {
        PaymentCommandService service = new PaymentCommandService(Clock.fixed(Instant.parse("2026-07-05T10:30:00Z"), ZoneOffset.UTC), envelope -> { });
        controller = new PaymentController(service, new IdempotencyStore());
        exceptionHandler = new PaymentExceptionHandler();
    }

    @Test
    void createPaymentIntentHappyPath() {
        ResponseEntity<?> response = createIntent("idem-create-1", "ord-1");
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        PaymentIntentResponse body = (PaymentIntentResponse) response.getBody();
        assertNotNull(body);
        assertTrue(body.paymentIntentId().startsWith("pi-"));
        assertEquals("ord-1", body.businessRef());
        assertEquals("CNY", body.amount().currency());
        assertEquals(35000L, body.amount().minorUnits());
        assertEquals("CREATED", body.status());
        assertEquals("2026-07-05T10:30:00Z", body.createdAt().toString());
    }

    @Test
    void cancelPaymentIntentHappyPath() {
        String id = ((PaymentIntentResponse) createIntent("idem-create-cancel", "ord-cancel").getBody()).paymentIntentId();
        ResponseEntity<?> response = controller.cancelPaymentIntent(id, "idem-cancel-1", new CancelPaymentIntentRequest("customer changed mind"), request("/api/v1/payment-intents/" + id + "/cancel"));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        CancelPaymentIntentResponse body = (CancelPaymentIntentResponse) response.getBody();
        assertEquals(id, body.paymentIntentId());
        assertEquals("CANCELLED", body.status());
        assertNotNull(body.cancelledAt());
    }

    @Test
    void capturePaymentHappyPath() {
        String id = ((PaymentIntentResponse) createIntent("idem-create-capture", "ord-capture").getBody()).paymentIntentId();
        ResponseEntity<?> response = controller.capturePayment(id, "idem-capture-1", request("/api/v1/payment-intents/" + id + "/capture"));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        CapturePaymentResponse body = (CapturePaymentResponse) response.getBody();
        assertEquals(id, body.paymentIntentId());
        assertEquals("CAPTURED", body.status());
        assertEquals(35000L, body.capturedAmount().minorUnits());
        assertEquals("txn-idem-capture-1", body.channelTransactionId());
    }

    @Test
    void requestRefundHappyPath() {
        String id = ((PaymentIntentResponse) createIntent("idem-create-refund", "ord-refund").getBody()).paymentIntentId();
        controller.capturePayment(id, "idem-capture-refund", request("/api/v1/payment-intents/" + id + "/capture"));
        ResponseEntity<?> response = controller.requestRefund("idem-refund-1", new RequestRefundRequest(id, new MoneyJson("CNY", 10000L), "ticket refund", "ps-1"), request("/api/v1/refunds"));
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        RefundResponse body = (RefundResponse) response.getBody();
        assertTrue(body.refundId().startsWith("rf-"));
        assertEquals(id, body.paymentIntentId());
        assertEquals(10000L, body.amount().minorUnits());
        assertEquals("REQUESTED", body.status());
    }

    @Test
    void getPaymentIntentHappyPath() {
        String id = ((PaymentIntentResponse) createIntent("idem-create-get", "ord-get").getBody()).paymentIntentId();
        PaymentIntentDetailsResponse body = controller.getPaymentIntent(id);
        assertEquals(id, body.paymentIntentId());
        assertEquals("purchase", body.purpose());
        assertNotNull(body.expiresAt());
    }

    @Test
    void getRefundHappyPath() {
        String id = ((PaymentIntentResponse) createIntent("idem-create-get-refund", "ord-get-refund").getBody()).paymentIntentId();
        controller.capturePayment(id, "idem-capture-get-refund", request("/api/v1/payment-intents/" + id + "/capture"));
        String refundId = ((RefundResponse) controller.requestRefund("idem-refund-get", new RequestRefundRequest(id, new MoneyJson("CNY", 10000L), "ticket refund", null), request("/api/v1/refunds")).getBody()).refundId();
        RefundDetailsResponse body = controller.getRefund(refundId);
        assertEquals(refundId, body.refundId());
        assertEquals("case-idem-refund-get", body.businessCaseRef());
    }

    @Test
    void validationFailureReturnsCanonicalBody() {
        MockHttpServletRequest request = request("/api/v1/payment-intents");
        request.removeHeader(RequestContextFilter.CORRELATION_ID_HEADER);
        request.addHeader(RequestContextFilter.CORRELATION_ID_HEADER, "corr-invalid");
        try {
            controller.createPaymentIntent("idem-invalid", new CreatePaymentIntentRequest("ord-1", null, null, null), request);
        } catch (ValidationException exception) {
            ResponseEntity<ErrorResponse> response = exceptionHandler.validation(exception, request);
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertEquals("VALIDATION_FAILED", response.getBody().code());
            assertEquals("corr-invalid", response.getBody().correlationId());
            assertTrue(response.getBody().details().isEmpty());
        }
    }

    @Test
    void idempotentReplayReturnsOriginalResult() {
        CreatePaymentIntentRequest body = new CreatePaymentIntentRequest("ord-replay", "purchase", new MoneyJson("CNY", 12345L), "acct-1");
        ResponseEntity<?> first = controller.createPaymentIntent("idem-replay", body, request("/api/v1/payment-intents"));
        ResponseEntity<?> second = controller.createPaymentIntent("idem-replay", body, request("/api/v1/payment-intents"));
        assertEquals(first, second);
    }

    private ResponseEntity<?> createIntent(String key, String businessRef) {
        return controller.createPaymentIntent(key, new CreatePaymentIntentRequest(businessRef, "purchase", new MoneyJson("CNY", 35000L), "acct-1"), request("/api/v1/payment-intents"));
    }

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.addHeader(RequestContextFilter.CORRELATION_ID_HEADER, "corr-test");
        return request;
    }
}
