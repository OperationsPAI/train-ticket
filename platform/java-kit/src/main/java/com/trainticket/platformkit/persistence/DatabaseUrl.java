package com.trainticket.platformkit.persistence;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public record DatabaseUrl(String jdbcUrl, String username, String password) {
    public DatabaseUrl {
        jdbcUrl = requireText(jdbcUrl, "jdbcUrl");
        username = username == null ? "" : username;
        password = password == null ? "" : password;
    }

    public static DatabaseUrl parse(String value) {
        URI uri = URI.create(requireText(value, "DATABASE_URL"));
        if (!"postgresql".equals(uri.getScheme()) && !"postgres".equals(uri.getScheme())) {
            throw new IllegalArgumentException("DATABASE_URL must use postgresql:// URI form");
        }
        String userInfo = uri.getRawUserInfo();
        String username = "";
        String password = "";
        if (userInfo != null && !userInfo.isBlank()) {
            String[] parts = userInfo.split(":", 2);
            username = decode(parts[0]);
            password = parts.length > 1 ? decode(parts[1]) : "";
        }
        String host = requireText(uri.getHost(), "DATABASE_URL host");
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String path = requireText(uri.getRawPath(), "DATABASE_URL database path");
        if ("/".equals(path)) {
            throw new IllegalArgumentException("DATABASE_URL database name is required");
        }
        StringBuilder jdbc = new StringBuilder("jdbc:postgresql://")
            .append(host)
            .append(':')
            .append(port)
            .append(path);
        if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
            jdbc.append('?').append(uri.getRawQuery());
        }
        return new DatabaseUrl(jdbc.toString(), username, password);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " is required").isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
