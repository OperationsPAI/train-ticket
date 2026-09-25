package com.trainticket.platformkit.persistence;

import java.time.Duration;

public final class EventReplayRetentionPolicy {
    public static final String ENVIRONMENT_VARIABLE = "EVENT_REPLAY_RETENTION_DAYS";
    public static final int DEFAULT_DAYS = 7;
    public static final int MAX_DAYS = 90;

    private EventReplayRetentionPolicy() {}

    public static Duration fromEnvironment() {
        return parse(System.getenv(ENVIRONMENT_VARIABLE));
    }

    static Duration parse(String configured) {
        if (configured == null || configured.isBlank()) return Duration.ofDays(DEFAULT_DAYS);
        try {
            int days = Integer.parseInt(configured.trim());
            if (days < 1 || days > MAX_DAYS) throw new IllegalArgumentException();
            return Duration.ofDays(days);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(ENVIRONMENT_VARIABLE + " must be an integer from 1 to " + MAX_DAYS);
        }
    }
}
