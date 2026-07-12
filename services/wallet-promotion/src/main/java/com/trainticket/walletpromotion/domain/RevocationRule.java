package com.trainticket.walletpromotion.domain;

import java.util.Map;

public record RevocationRule(Map<String, Object> conditions) {
    public RevocationRule { conditions = conditions == null ? Map.of() : Map.copyOf(conditions); }
}
