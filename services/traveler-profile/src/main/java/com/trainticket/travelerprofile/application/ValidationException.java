package com.trainticket.travelerprofile.application;

import com.trainticket.platformkit.http.ApiErrorCode;
import com.trainticket.platformkit.http.ApiException;
import java.util.Map;

public class ValidationException extends ApiException {
    public ValidationException(String message, Map<String, String> details) {
        super(ApiErrorCode.VALIDATION_FAILED, message, details);
    }
}
