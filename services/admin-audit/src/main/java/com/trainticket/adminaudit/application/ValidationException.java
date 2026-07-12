package com.trainticket.adminaudit.application;

import com.trainticket.platformkit.http.ApiErrorCode;
import com.trainticket.platformkit.http.ApiException;
import java.util.Map;

public class ValidationException extends ApiException {
    public ValidationException(String message) { super(ApiErrorCode.VALIDATION_FAILED, message, Map.of("validation", message)); }
    public ValidationException(String message, Throwable cause) { super(ApiErrorCode.VALIDATION_FAILED, message, Map.of("validation", message), cause); }
}
