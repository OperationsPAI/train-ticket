package com.trainticket.payment.adapters.http;

import com.trainticket.payment.RequestContextFilter;
import com.trainticket.payment.application.PaymentCommandService;
import com.trainticket.payment.domain.PaymentIntent;
import com.trainticket.payment.domain.Refund;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class PaymentController {
    private final PaymentCommandService service;

    public PaymentController(PaymentCommandService service) {
        this.service = Objects.requireNonNull(service, "service is required");
    }

    @PostMapping("/payment-intents")
    public ResponseEntity<?> createPaymentIntent(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody(required = false) CreatePaymentIntentRequest request, HttpServletRequest httpRequest) {
        validateCreate(request);
        PaymentIntent intent = service.createIntent(request.businessRef(), request.purpose(), PaymentHttpMapper.toMoney(request.amount()), request.payerRef(), idempotencyKey, correlationId(httpRequest), request.preferredChannel());
        return ResponseEntity.created(URI.create("/api/v1/payment-intents/" + intent.paymentIntentId())).body(PaymentHttpMapper.intentResponse(intent));
    }

    @PostMapping("/payment-intents/{paymentIntentId}/cancel")
    public ResponseEntity<?> cancelPaymentIntent(@PathVariable String paymentIntentId, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody(required = false) CancelPaymentIntentRequest request, HttpServletRequest httpRequest) {
        if (request == null || isBlank(request.reason())) {
            throw new ValidationException("reason is required");
        }
        return ResponseEntity.ok(PaymentHttpMapper.cancelResponse(service.cancelIntent(paymentIntentId, request.reason(), idempotencyKey, correlationId(httpRequest))));
    }

    public ResponseEntity<?> capturePayment(String paymentIntentId, String idempotencyKey, HttpServletRequest httpRequest) {
        return capturePayment(paymentIntentId, idempotencyKey, null, httpRequest);
    }

    @PostMapping("/payment-intents/{paymentIntentId}/capture")
    public ResponseEntity<?> capturePayment(@PathVariable String paymentIntentId, @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody(required = false) CapturePaymentRequest request, HttpServletRequest httpRequest) {
        ChannelRefJson channelRef = request == null ? null : request.channelRef();
        validateOptionalChannelRef(channelRef, false);
        PaymentIntent intent = service.captureIntent(paymentIntentId, idempotencyKey, correlationId(httpRequest), PaymentHttpMapper.toChannelRef(channelRef));
        return ResponseEntity.status(intent.channelRef() != null && intent.status().name().equals("CREATED") ? 202 : 200)
            .body(PaymentHttpMapper.captureResponse(intent, channelRef));
    }

    @PostMapping("/refunds")
    public ResponseEntity<?> requestRefund(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody(required = false) RequestRefundRequest request, HttpServletRequest httpRequest) {
        validateRefund(request);
        validateOptionalChannelRef(request.channelRef(), true);
        Refund refund = service.requestRefund(request.paymentIntentId(), PaymentHttpMapper.toMoney(request.amount()), request.reason(), request.businessCaseRef(), idempotencyKey, correlationId(httpRequest), PaymentHttpMapper.toChannelRef(request.channelRef()));
        return ResponseEntity.created(URI.create("/api/v1/refunds/" + refund.refundId())).body(PaymentHttpMapper.refundResponse(refund, request.channelRef()));
    }

    @GetMapping("/payment-intents/{paymentIntentId}")
    public PaymentIntentDetailsResponse getPaymentIntent(@PathVariable String paymentIntentId) {
        return PaymentHttpMapper.intentDetails(service.getIntent(paymentIntentId));
    }

    @GetMapping("/refunds/{refundId}")
    public RefundDetailsResponse getRefund(@PathVariable String refundId) {
        return PaymentHttpMapper.refundDetails(service.getRefund(refundId));
    }

    @GetMapping("/payment-intents/{paymentIntentId}/late-payment")
    public Map<String, Object> latePayment(@PathVariable String paymentIntentId) {
        var lateCase = service.latePaymentForIntent(paymentIntentId);
        return Map.of("latePaymentCaseId", lateCase.latePaymentCaseId(), "paymentIntentId", paymentIntentId,
            "status", lateCase.status().name(), "capturedAmount", PaymentHttpMapper.money(lateCase.capturedAmount()),
            "refundId", lateCase.refundId() == null ? "" : lateCase.refundId());
    }

    @PostMapping("/payment-intents/{paymentIntentId}/late-payment/refund")
    public RefundDetailsResponse refundLatePayment(@PathVariable String paymentIntentId,
        @RequestHeader("Idempotency-Key") String key, @RequestBody LateRefundRequest request, HttpServletRequest httpRequest) {
        requireText(request.businessRef(), "businessRef");
        requireText(request.businessCaseRef(), "businessCaseRef");
        requireText(request.reason(), "reason");
        service.prepareLatePaymentRefund(paymentIntentId, request.businessRef(), request.businessCaseRef(), request.reason(), key, correlationId(httpRequest));
        return PaymentHttpMapper.refundDetails(service.submitLatePaymentRefund(paymentIntentId, correlationId(httpRequest)));
    }

    @GetMapping("/payment-intents/by-business/{businessRef}")
    public PaymentIntentDetailsResponse latestIntentForBusiness(@PathVariable String businessRef) {
        return PaymentHttpMapper.intentDetails(service.latestIntentForBusiness(businessRef));
    }

    @GetMapping("/refunds")
    public Map<String, Object> refundsForCase(@RequestParam String businessCaseRef) {
        var items = service.refundsForCase(businessCaseRef).stream().map(PaymentHttpMapper::refundDetails).toList();
        return Map.of("items", items, "total", items.size());
    }

    @GetMapping("/internal/payment-intents/{paymentIntentId}/captured-facts")
    public Map<String, Object> capturedFacts(@PathVariable String paymentIntentId) {
        return service.capturedFacts(paymentIntentId);
    }

    private void validateCreate(CreatePaymentIntentRequest request) {
        if (request == null) {
            throw new ValidationException("request body is required");
        }
        requireText(request.businessRef(), "businessRef");
        requireText(request.purpose(), "purpose");
        PaymentHttpMapper.toMoney(request.amount());
        requireText(request.payerRef(), "payerRef");
        if (!isBlank(request.preferredChannel()) && !service.isSupportedChannel(request.preferredChannel())) {
            throw new ValidationException("preferredChannel is unsupported");
        }
    }

    private void validateOptionalChannelRef(ChannelRefJson channelRef, boolean requireOriginalRoute) {
        if (channelRef == null) {
            return;
        }
        requireText(channelRef.channel(), "channelRef.channel");
        if (!service.isSupportedChannel(channelRef.channel())) {
            throw new ValidationException("channelRef.channel is unsupported");
        }
        if (requireOriginalRoute) {
            requireText(channelRef.channelOrderId(), "channelRef.channelOrderId");
            requireText(channelRef.channelTransactionId(), "channelRef.channelTransactionId");
        }
    }

    private static void validateRefund(RequestRefundRequest request) {
        if (request == null) {
            throw new ValidationException("request body is required");
        }
        requireText(request.paymentIntentId(), "paymentIntentId");
        PaymentHttpMapper.toMoney(request.amount());
        requireText(request.reason(), "reason");
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
