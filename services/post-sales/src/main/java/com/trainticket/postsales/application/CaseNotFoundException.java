package com.trainticket.postsales.application;

import com.trainticket.platformkit.http.ApiErrorCode;
import com.trainticket.platformkit.http.ApiException;

public class CaseNotFoundException extends ApiException {
    public CaseNotFoundException(String caseId) {
        super(ApiErrorCode.NOT_FOUND, "post-sales case not found: " + caseId);
    }
}
