package com.trainticket.payment.application;

import com.trainticket.payment.domain.DomainRuleViolation;
import com.trainticket.payment.domain.Money;
import java.util.Map;

final class InboundEventPayload {
    private final Map<?, ?> payload;

    private InboundEventPayload(Object payload) {
        this.payload = asMap(payload, "payload");
    }

    static InboundEventPayload from(EventEnvelope envelope) {
        return new InboundEventPayload(envelope.payload());
    }

    String requiredText(String field) {
        Object value = payload.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new DomainRuleViolation(field + " is required");
        }
        return text;
    }

    String optionalText(String field) {
        Object value = payload.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new DomainRuleViolation(field + " must be text when present");
        }
        return text;
    }

    Money requiredMoney(String field) {
        Map<?, ?> money = asMap(payload.get(field), field);
        Object currency = money.get("currency");
        Object minorUnits = money.get("minorUnits");
        if (!(currency instanceof String currencyCode) || currencyCode.isBlank()) {
            throw new DomainRuleViolation(field + ".currency is required");
        }
        if (!(minorUnits instanceof Number number)) {
            throw new DomainRuleViolation(field + ".minorUnits is required");
        }
        return Money.fromMinorUnits(number.longValue(), currencyCode);
    }

    Money moneyFromApprovedActions() {
        Map<?, ?> approvedActions = asMap(payload.get("approvedActions"), "approvedActions");
        for (String field : new String[] { "refundAmount", "amount" }) {
            Object candidate = approvedActions.get(field);
            if (candidate != null) {
                return moneyFromObject(candidate, "approvedActions." + field);
            }
        }
        Object refund = approvedActions.get("refund");
        if (refund != null) {
            Map<?, ?> refundAction = asMap(refund, "approvedActions.refund");
            for (String field : new String[] { "amount", "refundAmount" }) {
                Object candidate = refundAction.get(field);
                if (candidate != null) {
                    return moneyFromObject(candidate, "approvedActions.refund." + field);
                }
            }
        }
        throw new DomainRuleViolation("approvedActions refund amount is required");
    }

    String paymentIntentIdFromApprovedActions() {
        Map<?, ?> approvedActions = asMap(payload.get("approvedActions"), "approvedActions");
        Object value = approvedActions.get("paymentIntentId");
        if (value == null && approvedActions.get("refund") != null) {
            value = asMap(approvedActions.get("refund"), "approvedActions.refund").get("paymentIntentId");
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new DomainRuleViolation("approvedActions.paymentIntentId is required");
        }
        return text;
    }

    private static Money moneyFromObject(Object value, String field) {
        Map<?, ?> money = asMap(value, field);
        Object currency = money.get("currency");
        Object minorUnits = money.get("minorUnits");
        if (!(currency instanceof String currencyCode) || currencyCode.isBlank()) {
            throw new DomainRuleViolation(field + ".currency is required");
        }
        if (!(minorUnits instanceof Number number)) {
            throw new DomainRuleViolation(field + ".minorUnits is required");
        }
        return Money.fromMinorUnits(number.longValue(), currencyCode);
    }

    private static Map<?, ?> asMap(Object value, String field) {
        if (value == null) {
            throw new DomainRuleViolation(field + " is required");
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new DomainRuleViolation(field + " must be an object");
        }
        return map;
    }
}
