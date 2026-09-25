package com.trainticket.walletpromotion.application;

import com.trainticket.walletpromotion.domain.*;

public record ReverseRedemptionCommand(String redemptionId, String reversalRef, Money amount, BusinessReason businessReason) {}
