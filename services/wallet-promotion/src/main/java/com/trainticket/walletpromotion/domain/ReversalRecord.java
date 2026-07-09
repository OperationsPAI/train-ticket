package com.trainticket.walletpromotion.domain;

import java.time.Instant;

public record ReversalRecord(String reversalId, String redemptionId, Money amount, BusinessReason businessReason, Instant reversedAt) {}
