package com.trainticket.payment.application;

import com.trainticket.platformkit.http.ApiErrorCode;
import com.trainticket.platformkit.http.ApiException;

public class NotFoundException extends ApiException {
    public NotFoundException(String message) {
        super(ApiErrorCode.NOT_FOUND, message);
    }
}
