package com.trainticket.walletpromotion.application;

import com.trainticket.walletpromotion.domain.*;

public record RedeemBenefitCommand(Money amount, String redemptionRef, String reservationRef, BusinessReason businessReason) {}
