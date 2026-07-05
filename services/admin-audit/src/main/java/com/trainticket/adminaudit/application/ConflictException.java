package com.trainticket.adminaudit.application;

import com.trainticket.platformkit.http.ApiErrorCode;
import com.trainticket.platformkit.http.ApiException;

public class ConflictException extends ApiException {
    public ConflictException(String message) { super(ApiErrorCode.CONFLICT, message); }
    public ConflictException(String message, Throwable cause) { super(ApiErrorCode.CONFLICT, message, cause); }
}
