package com.trainticket.platformkit.messaging;

import java.security.SecureRandom;
import java.util.UUID;

public final class UuidV7 {
    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {}

    public static UUID generate() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        long millis = System.currentTimeMillis();
        bytes[0] = (byte) (millis >>> 40);
        bytes[1] = (byte) (millis >>> 32);
        bytes[2] = (byte) (millis >>> 24);
        bytes[3] = (byte) (millis >>> 16);
        bytes[4] = (byte) (millis >>> 8);
        bytes[5] = (byte) millis;
        bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x70);
        bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
        long most = 0;
        long least = 0;
        for (int i = 0; i < 8; i++) most = (most << 8) | (bytes[i] & 0xffL);
        for (int i = 8; i < 16; i++) least = (least << 8) | (bytes[i] & 0xffL);
        return new UUID(most, least);
    }

    public static String generateString() { return generate().toString(); }

    public static boolean isUuidV7(String value) {
        try { return UUID.fromString(value).version() == 7; } catch (RuntimeException ex) { return false; }
    }

    public static String eventId() { return "evt-" + generateString(); }
    public static String commandId() { return "cmd-" + generateString(); }
    public static String correlationId() { return "corr-" + generateString(); }
}
