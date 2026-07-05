package com.trainticket.financesettlement.application;

import com.trainticket.platformkit.http.ApiErrorCode;
import com.trainticket.platformkit.http.ApiException;

public class ValidationException extends ApiException {
    public ValidationException(String message) {
        super(ApiErrorCode.VALIDATION_FAILED, message);
    }
}
