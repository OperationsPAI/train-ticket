package com.trainticket.postsales.domain;

import com.trainticket.platformkit.http.ApiErrorCode;
import com.trainticket.platformkit.http.ApiException;

public final class DomainRuleViolation extends ApiException {
    public DomainRuleViolation(String message) {
        super(ApiErrorCode.DOMAIN_RULE_VIOLATION, message);
    }
}
