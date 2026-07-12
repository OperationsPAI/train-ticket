package com.trainticket.walletpromotion.application;

import com.trainticket.walletpromotion.domain.*;
import java.time.Instant;

public record ReserveBenefitCommand(Money amount, String reservationRef, Instant reservationExpiresAt, BusinessReason businessReason) {}
