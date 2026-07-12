package com.trainticket.postsales.domain;

import java.util.List;
import java.util.Objects;

public record PostSalesScope(
    List<String> orderItemRefs,
    List<String> segmentRefs,
    List<String> travelerRefs,
    List<String> entitlementRefs
) {
    public PostSalesScope {
        orderItemRefs = copyNonBlank(orderItemRefs, "orderItemRefs");
        segmentRefs = copyNonBlank(segmentRefs, "segmentRefs");
        travelerRefs = copyNonBlank(travelerRefs, "travelerRefs");
        entitlementRefs = copyNonBlank(entitlementRefs, "entitlementRefs");
        if (orderItemRefs.isEmpty() && segmentRefs.isEmpty() && travelerRefs.isEmpty() && entitlementRefs.isEmpty()) {
            throw new DomainRuleViolation("post-sales scope must explicitly reference affected order items, segments, travelers, or entitlements");
        }
    }

    public static PostSalesScope ticket(String orderItemRef, String segmentRef, String travelerRef, String entitlementRef) {
        return new PostSalesScope(List.of(orderItemRef), List.of(segmentRef), List.of(travelerRef), List.of(entitlementRef));
    }

    public boolean includesEntitlement(String entitlementRef) {
        return entitlementRefs.contains(entitlementRef);
    }

    private static List<String> copyNonBlank(List<String> values, String name) {
        Objects.requireNonNull(values, name + " are required");
        for (String value : values) {
            requireText(value, name + " entry");
        }
        return List.copyOf(values);
    }

    static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new DomainRuleViolation(name + " must not be blank");
        }
        return value;
    }
}
