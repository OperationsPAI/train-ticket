package com.trainticket.walletpromotion.infrastructure.persistence;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

final class DeterministicEventIds {
    private DeterministicEventIds() {
    }

    static String forTransition(String eventType, String benefitId, long aggregateVersion) {
        String seed = "wallet-promotion:" + eventType + ":" + benefitId + ":" + aggregateVersion;
        byte[] hash = sha256(seed);
        ByteBuffer buffer = ByteBuffer.wrap(hash);
        long most = buffer.getLong();
        long least = buffer.getLong();
        most = (most & 0xffffffffffff0fffL) | 0x0000000000007000L;
        least = (least & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return "evt-" + new UUID(most, least);
    }

    private static byte[] sha256(String seed) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
