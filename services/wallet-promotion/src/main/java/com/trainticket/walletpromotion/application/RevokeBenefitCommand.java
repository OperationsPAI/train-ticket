package com.trainticket.walletpromotion.application;

import com.trainticket.walletpromotion.domain.*;
import java.time.Instant;

public record RevokeBenefitCommand(BusinessReason businessReason, Instant effectiveAt) {}
