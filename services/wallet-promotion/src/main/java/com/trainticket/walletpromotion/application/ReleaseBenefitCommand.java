package com.trainticket.walletpromotion.application;

import com.trainticket.walletpromotion.domain.*;

public record ReleaseBenefitCommand(Money amount, String reservationRef, BusinessReason businessReason) {}
