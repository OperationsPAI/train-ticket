package com.trainticket.financesettlement.application;

import com.trainticket.platformkit.http.ApiErrorCode;
import com.trainticket.platformkit.http.ApiException;

public class ResourceNotFoundException extends ApiException {
    public ResourceNotFoundException(String message) {
        super(ApiErrorCode.NOT_FOUND, message);
    }
}
