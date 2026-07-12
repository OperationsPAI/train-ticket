package com.trainticket.adminaudit.application;

import com.trainticket.platformkit.http.ApiErrorCode;
import com.trainticket.platformkit.http.ApiException;

public class NotFoundException extends ApiException {
    public NotFoundException(String message) { super(ApiErrorCode.NOT_FOUND, message); }
    public NotFoundException(String message, Throwable cause) { super(ApiErrorCode.NOT_FOUND, message, cause); }
}
