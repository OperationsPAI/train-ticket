package com.trainticket.platformkit.idempotency;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class UuidV7 {
    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static String generate() {
        return generate(Instant.now());
    }

    public static String generate(Instant now) {
        Objects.requireNonNull(now, "now is required");
        long unixMillis = now.toEpochMilli();
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);

        long most = ((unixMillis & 0xFFFF_FFFF_FFFFL) << 16)
            | 0x7000L
            | (random[0] & 0x0FFFL);
        long least = 0x8000_0000_0000_0000L
            | ((long) (random[1] & 0x3F) << 56)
            | ((long) (random[2] & 0xFF) << 48)
            | ((long) (random[3] & 0xFF) << 40)
            | ((long) (random[4] & 0xFF) << 32)
            | ((long) (random[5] & 0xFF) << 24)
            | ((long) (random[6] & 0xFF) << 16)
            | ((long) (random[7] & 0xFF) << 8)
            | ((long) (random[8] & 0xFF));
        return new UUID(most, least).toString();
    }

    public static boolean isValid(String value) {
        if (value == null) {
            return false;
        }
        try {
            UUID uuid = UUID.fromString(value.toLowerCase(Locale.ROOT));
            return uuid.version() == 7 && uuid.variant() == 2 && uuid.toString().equals(value.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public static void requireValid(String value, String name) {
        if (!isValid(value)) {
            throw new IllegalArgumentException(name + " must be a UUID v7");
        }
    }
}
