package com.trainticket.platformkit.idempotency;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

public final class RequestFingerprint {
    private RequestFingerprint() {
    }

    public static String of(HttpServletRequest request, byte[] body) {
        String target = request.getMethod().toUpperCase() + " " + request.getRequestURI()
            + "?" + Optional.ofNullable(request.getQueryString()).orElse("") + "\n";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(target.getBytes(StandardCharsets.UTF_8));
            digest.update(body == null ? new byte[0] : body);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
