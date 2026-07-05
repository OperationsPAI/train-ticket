package com.trainticket.adminaudit.application;

import com.trainticket.platformkit.http.ApiErrorCode;
import com.trainticket.platformkit.http.ApiException;

public class PreconditionFailedException extends ApiException {
    public PreconditionFailedException(String message) { super(ApiErrorCode.PRECONDITION_FAILED, message); }
    public PreconditionFailedException(String message, Throwable cause) { super(ApiErrorCode.PRECONDITION_FAILED, message, cause); }
}
