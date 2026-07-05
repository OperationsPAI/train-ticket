package com.trainticket.financesettlement.application;

import com.trainticket.platformkit.http.ApiErrorCode;
import com.trainticket.platformkit.http.ApiException;

public class PublishFailedException extends ApiException {
    public PublishFailedException(String message, Throwable cause) {
        super(ApiErrorCode.UNAVAILABLE, message, cause);
    }
}
