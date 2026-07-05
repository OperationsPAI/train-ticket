package com.trainticket.payment.adapters.http;

import com.trainticket.payment.RequestContextFilter;
import com.trainticket.payment.application.PaymentCommandService;
import com.trainticket.payment.domain.PaymentIntent;
import com.trainticket.payment.domain.Refund;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class PaymentController {
    private final PaymentCommandService service;
    private final IdempotencyStore idempotencyStore;

    public PaymentController(PaymentCommandService service, IdempotencyStore idempotencyStore) {
        this.service = Objects.requireNonNull(service, "service is required");
        this.idempotencyStore = Objects.requireNonNull(idempotencyStore, "idempotencyStore is required");
    }

    @PostMapping("/payment-intents")
    public ResponseEntity<?> createPaymentIntent(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody(required = false) CreatePaymentIntentRequest request, HttpServletRequest httpRequest) {
        requireIdempotencyKey(idempotencyKey);
        validateCreate(request);
        return idempotencyStore.replayOrRecord("POST", httpRequest.getRequestURI(), idempotencyKey, request, () -> {
            PaymentIntent intent = service.createIntent(request.businessRef(), request.purpose(), PaymentHttpMapper.toMoney(request.amount()), request.payerRef(), idempotencyKey, correlationId(httpRequest));
            return ResponseEntity.created(URI.create("/api/v1/payment-intents/" + intent.paymentIntentId())).body(PaymentHttpMapper.intentResponse(intent));
        });
    }

    @PostMapping("/payment-intents/{paymentIntentId}/cancel")
    public ResponseEntity<?> cancelPaymentIntent(@PathVariable String paymentIntentId, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody(required = false) CancelPaymentIntentRequest request, HttpServletRequest httpRequest) {
        requireIdempotencyKey(idempotencyKey);
        if (request == null || isBlank(request.reason())) {
            throw new ValidationException("reason is required");
        }
        return idempotencyStore.replayOrRecord("POST", httpRequest.getRequestURI(), idempotencyKey, request, () -> ResponseEntity.ok(PaymentHttpMapper.cancelResponse(service.cancelIntent(paymentIntentId, request.reason(), idempotencyKey, correlationId(httpRequest)))));
    }

    @PostMapping("/payment-intents/{paymentIntentId}/capture")
    public ResponseEntity<?> capturePayment(@PathVariable String paymentIntentId, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, HttpServletRequest httpRequest) {
        requireIdempotencyKey(idempotencyKey);
        return idempotencyStore.replayOrRecord("POST", httpRequest.getRequestURI(), idempotencyKey, "", () -> ResponseEntity.ok(PaymentHttpMapper.captureResponse(service.captureIntent(paymentIntentId, idempotencyKey, correlationId(httpRequest)))));
    }

    @PostMapping("/refunds")
    public ResponseEntity<?> requestRefund(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody(required = false) RequestRefundRequest request, HttpServletRequest httpRequest) {
        requireIdempotencyKey(idempotencyKey);
        validateRefund(request);
        return idempotencyStore.replayOrRecord("POST", httpRequest.getRequestURI(), idempotencyKey, request, () -> {
            Refund refund = service.requestRefund(request.paymentIntentId(), PaymentHttpMapper.toMoney(request.amount()), request.reason(), request.businessCaseRef(), idempotencyKey, correlationId(httpRequest));
            return ResponseEntity.created(URI.create("/api/v1/refunds/" + refund.refundId())).body(PaymentHttpMapper.refundResponse(refund));
        });
    }

    @GetMapping("/payment-intents/{paymentIntentId}")
    public PaymentIntentDetailsResponse getPaymentIntent(@PathVariable String paymentIntentId) {
        return PaymentHttpMapper.intentDetails(service.getIntent(paymentIntentId));
    }

    @GetMapping("/refunds/{refundId}")
    public RefundDetailsResponse getRefund(@PathVariable String refundId) {
        return PaymentHttpMapper.refundDetails(service.getRefund(refundId));
    }

    private static void validateCreate(CreatePaymentIntentRequest request) {
        if (request == null) {
            throw new ValidationException("request body is required");
        }
        requireText(request.businessRef(), "businessRef");
        requireText(request.purpose(), "purpose");
        PaymentHttpMapper.toMoney(request.amount());
        requireText(request.payerRef(), "payerRef");
    }

    private static void validateRefund(RequestRefundRequest request) {
        if (request == null) {
            throw new ValidationException("request body is required");
        }
        requireText(request.paymentIntentId(), "paymentIntentId");
        PaymentHttpMapper.toMoney(request.amount());
        requireText(request.reason(), "reason");
    }

    private static void requireIdempotencyKey(String idempotencyKey) {
        requireText(idempotencyKey, "Idempotency-Key");
    }

    private static String requireText(String value, String field) {
        if (isBlank(value)) {
            throw new ValidationException(field + " is required");
        }
        return value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String correlationId(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestContextFilter.CORRELATION_ID_ATTRIBUTE);
        if (attribute != null) {
            return attribute.toString();
        }
        String header = request.getHeader(RequestContextFilter.CORRELATION_ID_HEADER);
        return header == null ? "" : header;
    }
}
