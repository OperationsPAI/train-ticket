package com.trainticket.payment.adapters.http;

import com.trainticket.platformkit.http.ApiErrorCode;
import com.trainticket.platformkit.http.ApiException;

class ValidationException extends ApiException {
    ValidationException(String message) {
        super(ApiErrorCode.VALIDATION_FAILED, message);
    }
}
