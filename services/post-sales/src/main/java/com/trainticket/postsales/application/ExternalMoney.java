package com.trainticket.postsales.application;

import java.util.Map;

public record ExternalMoney(String currency, long minorUnits) {
    public ExternalMoney {
        currency = requireText(currency, "currency");
    }

    static ExternalMoney fromPayloadValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Object currencyValue = map.get("currency");
        Object minorUnitsValue = map.get("minorUnits");
        if (!(currencyValue instanceof String currency) || currency.isBlank()) {
            return null;
        }
        if (minorUnitsValue instanceof Number number) {
            return new ExternalMoney(currency, number.longValue());
        }
        return null;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
