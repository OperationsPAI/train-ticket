package com.trainticket.platformkit.http;

import java.util.Map;

public class ApiException extends RuntimeException {
    private final ApiErrorCode code;
    private final Map<String, ?> details;

    public ApiException(ApiErrorCode code, String message) {
        this(code, message, Map.of(), null);
    }

    public ApiException(ApiErrorCode code, String message, Map<String, ?> details) {
        this(code, message, details, null);
    }

    public ApiException(ApiErrorCode code, String message, Throwable cause) {
        this(code, message, Map.of(), cause);
    }

    public ApiException(ApiErrorCode code, String message, Map<String, ?> details, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public ApiErrorCode code() {
        return code;
    }

    public Map<String, ?> details() {
        return details;
    }
}
