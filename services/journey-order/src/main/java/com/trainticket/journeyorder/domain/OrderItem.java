package com.trainticket.journeyorder.domain;

import java.util.List;
import java.util.Objects;

public final class OrderItem {
    private final String orderItemId;
    private final OrderItemType type;
    private final String description;
    private final Money amount;
    private final String commercialReasonRef;
    private final List<OrderLineBinding> bindings;
    private boolean cancelled;
    private String cancellationReason;

    public OrderItem(
        String orderItemId,
        OrderItemType type,
        String description,
        Money amount,
        String commercialReasonRef,
        List<OrderLineBinding> bindings
    ) {
        this.orderItemId = requireText(orderItemId, "orderItemId");
        this.type = Objects.requireNonNull(type, "type is required");
        this.description = requireText(description, "description");
        this.amount = Objects.requireNonNull(amount, "amount is required");
        this.commercialReasonRef = requireText(commercialReasonRef, "commercialReasonRef");
        this.bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings are required"));
        if (this.bindings.isEmpty() && type != OrderItemType.SERVICE_FEE && type != OrderItemType.DISCOUNT && type != OrderItemType.TAX) {
            throw new DomainRuleViolation("chargeable segment or ancillary order item requires traveler/line binding");
        }
        if (type == OrderItemType.DISCOUNT && amount.amount().signum() > 0) {
            throw new DomainRuleViolation("discount order item amount must be zero or negative");
        }
        if (type != OrderItemType.DISCOUNT && amount.isNegative()) {
            throw new DomainRuleViolation(type + " order item amount must not be negative");
        }
    }

    public String orderItemId() {
        return orderItemId;
    }

    public OrderItemType type() {
        return type;
    }

    public String description() {
        return description;
    }

    public Money amount() {
        return amount;
    }

    public String commercialReasonRef() {
        return commercialReasonRef;
    }

    public List<OrderLineBinding> bindings() {
        return bindings;
    }

    public boolean cancelled() {
        return cancelled;
    }

    public String cancellationReason() {
        return cancellationReason;
    }

    public void cancel(String reason) {
        if (cancelled) {
            return;
        }
        cancelled = true;
        cancellationReason = requireText(reason, "reason");
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
        return value;
    }
}
