package com.trainticket.walletpromotion.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RedemptionRule(boolean singleUse, Money maxRedemptionAmount, boolean requiresReservation) {}
