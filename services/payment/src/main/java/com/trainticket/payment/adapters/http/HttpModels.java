package com.trainticket.payment.adapters.http;

import java.time.Instant;
import java.util.Map;

record MoneyJson(String currency, Long minorUnits) {
}

record CreatePaymentIntentRequest(String businessRef, String purpose, MoneyJson amount, String payerRef) {
}

record PaymentIntentResponse(String paymentIntentId, String businessRef, MoneyJson amount, String status, Instant createdAt) {
}

record PaymentIntentDetailsResponse(
    String paymentIntentId,
    String businessRef,
    String purpose,
    MoneyJson amount,
    String payerRef,
    String status,
    Instant createdAt,
    Instant expiresAt,
    MoneyJson authorizedAmount,
    MoneyJson capturedAmount,
    MoneyJson refundedAmount
) {
}

record CancelPaymentIntentRequest(String reason) {
}

record CancelPaymentIntentResponse(String paymentIntentId, String status, Instant cancelledAt) {
}

record CapturePaymentResponse(String paymentIntentId, String status, MoneyJson capturedAmount, String channelTransactionId) {
}

record RequestRefundRequest(String paymentIntentId, MoneyJson amount, String reason, String businessCaseRef) {
}

record RefundResponse(String refundId, String paymentIntentId, MoneyJson amount, String status) {
}

record RefundDetailsResponse(
    String refundId,
    String paymentIntentId,
    MoneyJson amount,
    String status,
    String reason,
    String businessCaseRef,
    String channelRefundTransactionId
) {
}

record ErrorResponse(String code, String message, String correlationId, Map<String, Object> details) {
}
