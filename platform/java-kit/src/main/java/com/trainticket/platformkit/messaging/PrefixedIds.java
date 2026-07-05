package com.trainticket.platformkit.messaging;

import com.trainticket.platformkit.idempotency.UuidV7;

public final class PrefixedIds {
    private PrefixedIds() {
    }

    public static String newEventId() {
        return "evt-" + UuidV7.generate();
    }

    public static String newCorrelationId() {
        return "corr-" + UuidV7.generate();
    }

    public static String newCommandId() {
        return "cmd-" + UuidV7.generate();
    }

    public static boolean isEventId(String value) {
        return hasValidUuidWithPrefix(value, "evt-");
    }

    public static boolean isCorrelationId(String value) {
        return hasValidUuidWithPrefix(value, "corr-");
    }

    public static boolean isCausationId(String value) {
        return hasValidUuidWithPrefix(value, "cmd-") || hasValidUuidWithPrefix(value, "evt-");
    }

    public static void requireEventId(String value) {
        if (!isEventId(value)) {
            throw new IllegalArgumentException("eventId must be evt- + UUID v7");
        }
    }

    public static void requireCorrelationId(String value) {
        if (!isCorrelationId(value)) {
            throw new IllegalArgumentException("correlationId must be corr- + UUID v7");
        }
    }

    public static void requireCausationId(String value) {
        if (!isCausationId(value)) {
            throw new IllegalArgumentException("causationId must be cmd-/evt- + UUID v7");
        }
    }

    private static boolean hasValidUuidWithPrefix(String value, String prefix) {
        return value != null && value.startsWith(prefix) && UuidV7.isValid(value.substring(prefix.length()));
    }
}
