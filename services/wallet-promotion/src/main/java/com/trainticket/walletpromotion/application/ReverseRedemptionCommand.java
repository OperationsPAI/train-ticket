package com.trainticket.walletpromotion.application;

import com.trainticket.walletpromotion.domain.*;

public record ReverseRedemptionCommand(String redemptionId, Money amount, BusinessReason businessReason) {}
