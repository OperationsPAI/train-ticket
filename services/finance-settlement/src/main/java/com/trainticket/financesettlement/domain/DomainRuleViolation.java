package com.trainticket.financesettlement.domain;

import com.trainticket.platformkit.http.ApiErrorCode;
import com.trainticket.platformkit.http.ApiException;

public class DomainRuleViolation extends ApiException {
    public DomainRuleViolation(String message) {
        super(ApiErrorCode.DOMAIN_RULE_VIOLATION, message);
    }
}
